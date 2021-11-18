package com.rallyhealth.weepickle.v1.implicits

import compiletime.summonInline
import deriving.Mirror
import scala.compiletime.{erasedValue, error}
import scala.reflect.ClassTag
import com.rallyhealth.weepickle.v1.core.{Annotator, ObjVisitor, Types, Visitor}

trait CaseClassFromPiece extends MacrosCommon:
  this: Types with Annotator =>

  private def caseClassFrom[V](
    // parallel arrays in field definition order
    fieldNames: Array[String],
    defaultValues: Array[Option[() => AnyRef]],
    createFroms: => Array[From[_]],
    dropDefaults: Array[Boolean],
    dropAllDefaults: Boolean
  ): CaseW[V] = new CaseW[V] {

    private lazy val froms = createFroms

    override def length(v: V): Int =
      if mightDropDefaults then
        var sum = 0
        val product = v.asInstanceOf[Product]
        var i = 0
        val arity = product.productArity
        while (i < arity) do
          val value = product.productElement(i)
          val writer = froms(i)
          if shouldWriteValue(value, i) then sum += 1
          i += 1
        sum
      else
      // fast path
        froms.length
    end length

    override def writeToObject[R](ctx: ObjVisitor[_, R], v: V): Unit =
      val product = v.asInstanceOf[Product]
      var i = 0
      val arity = product.productArity
      while (i < arity) do
        val value = product.productElement(i)
        if shouldWriteValue(value, i) then
          val from = froms(i)
          val fieldName = fieldNames(i)
          val keyVisitor = ctx.visitKey()
          ctx.visitKeyValue(
            keyVisitor.visitString(
              objectAttributeKeyWriteMap(fieldName)
            )
          )
          ctx.narrow.visitValue(from.narrow.transform(value, ctx.subVisitor))
        end if
        i += 1
      end while
    end writeToObject

    /**
     * Optimization to allow short-circuiting length checks.
     */
    private val mightDropDefaults = !serializeDefaults && (dropAllDefaults || dropDefaults.exists(_ == true)) && defaultValues.exists(_.isDefined)

    private def shouldWriteValue(value: Any, i: Int): Boolean =
      serializeDefaults || !(dropAllDefaults || dropDefaults(i)) || !defaultValues(i).exists(_.apply() == value)

  }

  inline def macroFrom[T: ClassTag](using m: Mirror.Of[T]): From[T] = inline m match {
    case m: Mirror.ProductOf[T] =>
      val (fullClassName, dropAllDefaults) = macros.fullClassName[T]

      // parallel arrays in field definition order
      val labels: List[(String, Boolean)] = macros.fieldLabels[T]
      val fieldNames = labels.map(_._1).toArray
      val dropDefaults = labels.map(_._2).toArray

      /**
       * defaultValues must be evaluated each time to handle changing values
       * like System.currentTimeMillis. Covered by ChangingDefaultTests.
       */
      val defaultValues = fieldNames.map(macros.getDefaultParams[T])

      /**
       * froms must be lazy to handle deeply nested `def pickler = macroFromTo` structures.
       * `val pickler = macroFromTo` is always preferred.
       * Part of the problem is that `FromTo` is required even when only a From or To is needed.
       * Covered by MacroTests.exponential.
       */
      def createFroms: Array[From[_]] =
        macros.summonList[Tuple.Map[m.MirroredElemTypes, From]].asInstanceOf[List[From[_]]].toArray

      val fromCaseClass = caseClassFrom[T](
        fieldNames,
        defaultValues,
        createFroms,
        dropDefaults,
        dropAllDefaults,
      )

      val (isSealed, discriminator) = macros.isMemberOfSealedHierarchy[T]
      if isSealed then annotate(fromCaseClass, discriminator.getOrElse(tagName), fullClassName)
      else fromCaseClass
    case m: Mirror.SumOf[T] =>
      val writers: List[From[_ <: T]] = macros.summonList[Tuple.Map[m.MirroredElemTypes, From]]
        .asInstanceOf[List[From[_ <: T]]]
      From.merge[T](writers:_*)
  }

  /*
   * Default string encoding for Scala 3 enums. Mirror should always be for the sealed trait generated by the
   * enum, which is a Sum type. So any calls here with Product type mirrors are rejected as compilation errors.
   */
  inline def macroEnumFrom[T: ClassTag](using m: Mirror.Of[T]): From[T] = inline m match {
    case m: Mirror.ProductOf[T] =>
      error("Enumeration macro used for non-sealed trait")

    case m: Mirror.SumOf[T] =>
      new From[T] {
        def transform0[Out](in: T, out: Visitor[_, Out]): Out =
          out.visitString(in.toString)
      }
  }

  inline given [T <: Singleton: Mirror.Of: ClassTag]: From[T] = macroFrom[T]

  // see comment in MacroImplicits as to why Dotty's extension methods aren't used here
  implicit class FromExtension(r: From.type):
    inline def derived[T](using Mirror.Of[T], ClassTag[T]): From[T] = inline erasedValue[T] match
      case _: scala.reflect.Enum => macroEnumFrom[T]
      case _ => macroFrom[T]
  end FromExtension

end CaseClassFromPiece