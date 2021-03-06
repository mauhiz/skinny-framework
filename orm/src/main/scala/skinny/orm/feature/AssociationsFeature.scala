package skinny.orm.feature

import scala.language.existentials

import skinny.logging.Logging
import skinny.orm._
import skinny.orm.feature.includes.IncludesQueryRepository
import skinny.orm.feature.associations._
import scalikejdbc._, SQLInterpolation._
import scala.collection.mutable
import skinny.util.JavaReflectAPI
import skinny.orm.exception.AssociationSettingsException

object AssociationsFeature {

  def defaultIncludesMerge[Entity, A] = (es: Seq[Entity], as: Seq[A]) =>
    throw new AssociationSettingsException(
      """
        |--------- Invalid Association Settings ---------
        |
        |  Merge function for includes query is required.
        |
        |  e.g.
        |
        |  val company = belongsTo[Company](Company, (e, c) => e.copy(company = c))
        |    .includes[Company]((es, cs) => es.map { e =>
        |      cs.find(c => e.exists(_.id == c.id)).map(v => e.copy(company = Some(v))).getOrElse(e)
        |    }
        |
        |-------------------------------------------------
        |""".stripMargin)

}

/**
 * Associations support feature.
 *
 * @tparam Entity entity
 */
trait AssociationsFeature[Entity]
    extends SkinnyMapperBase[Entity]
    with ConnectionPoolFeature
    with AutoSessionFeature
    with Logging { self: SQLSyntaxSupport[Entity] =>

  import AssociationsFeature._

  /**
   * Associations
   */
  def associations = new mutable.LinkedHashSet[Association[_]]

  private[skinny] def belongsToAssociations: Seq[BelongsToAssociation[Entity]] = {
    associations.filter(_.isInstanceOf[BelongsToAssociation[Entity]])
      .map(_.asInstanceOf[BelongsToAssociation[Entity]]).toSeq
  }
  private[skinny] def hasOneAssociations: Seq[HasOneAssociation[Entity]] = {
    associations.filter(_.isInstanceOf[HasOneAssociation[Entity]])
      .map(_.asInstanceOf[HasOneAssociation[Entity]]).toSeq
  }
  private[skinny] def hasManyAssociations: Seq[HasManyAssociation[Entity]] = {
    associations.filter(_.isInstanceOf[HasManyAssociation[Entity]])
      .map(_.asInstanceOf[HasManyAssociation[Entity]]).toSeq
  }

  /**
   * Join definitions that are enabled by default.
   */
  val defaultJoinDefinitions = new mutable.LinkedHashSet[JoinDefinition[_]]()

  private def unshiftJoinDefinition(newOne: JoinDefinition[_], definitions: mutable.LinkedHashSet[JoinDefinition[_]]) = {
    val newDefinitions = new mutable.LinkedHashSet[JoinDefinition[_]]()
    newDefinitions.add(newOne)
    newDefinitions ++= definitions
  }

  // ----------------------
  // Join Definition
  // ----------------------

  /**
   * Creates a new join definition.
   *
   * @param joinType join type
   * @param left left mapper and table alias
   * @param right right mapper and table alias
   * @param on join condition
   * @return join definition
   */
  def createJoinDefinition(joinType: JoinType, left: (AssociationsFeature[_], Alias[_]), right: (AssociationsFeature[_], Alias[_]), on: SQLSyntax): JoinDefinition[Entity] = {
    val (leftMapper, leftAlias) = left
    val (rightMapper, rightAlias) = right
    JoinDefinition[Entity](
      joinType,
      this,
      leftMapper.asInstanceOf[AssociationsFeature[Any]],
      leftAlias.asInstanceOf[Alias[Any]],
      rightMapper.asInstanceOf[AssociationsFeature[Any]],
      rightAlias.asInstanceOf[Alias[Any]],
      on
    )
  }

  // ----------------------
  // Inner Join Definition
  // ----------------------

  // using default alias

  def joinWithDefaults(right: AssociationsFeature[_], on: SQLSyntax): JoinDefinition[Entity] = {
    innerJoinWithDefaults(right, on)
  }
  def joinWithDefaults(right: AssociationsFeature[_], on: (Alias[Entity], Alias[Any]) => SQLSyntax): JoinDefinition[Entity] = {
    innerJoinWithDefaults(right, on)
  }
  def joinWithDefaults[Left](left: AssociationsFeature[Left], right: AssociationsFeature[_], on: (Alias[Left], Alias[_]) => SQLSyntax): JoinDefinition[Entity] = {
    innerJoinWithDefaults[Left](left, right, on)
  }

  def innerJoinWithDefaults(right: AssociationsFeature[_], on: SQLSyntax): JoinDefinition[Entity] = {
    createJoinDefinition(InnerJoin, this -> this.defaultAlias, right -> right.defaultAlias, on)
  }
  def innerJoinWithDefaults(right: AssociationsFeature[_], on: (Alias[Entity], Alias[Any]) => SQLSyntax): JoinDefinition[Entity] = {
    createJoinDefinition(InnerJoin, this -> this.defaultAlias, right -> right.defaultAlias, on.apply(this.defaultAlias, right.defaultAlias.asInstanceOf[Alias[Any]]))
  }
  def innerJoinWithDefaults[Left](left: AssociationsFeature[Left], right: AssociationsFeature[_], on: (Alias[Left], Alias[_]) => SQLSyntax): JoinDefinition[Entity] = {
    createJoinDefinition(InnerJoin, left -> left.defaultAlias, right -> right.defaultAlias, on.apply(left.defaultAlias, right.defaultAlias))
  }

  // using specified alias

  def join(right: (AssociationsFeature[_], Alias[_]), on: (Alias[Entity], Alias[_]) => SQLSyntax): JoinDefinition[Entity] = {
    innerJoin(right, on)
  }
  def join[Left](left: (AssociationsFeature[Left], Alias[Left]), right: (AssociationsFeature[_], Alias[_]), on: (Alias[Left], Alias[_]) => SQLSyntax): JoinDefinition[Entity] = {
    innerJoin(left, right, on)
  }
  def innerJoin(right: (AssociationsFeature[_], Alias[_]), on: (Alias[Entity], Alias[_]) => SQLSyntax): JoinDefinition[Entity] = {
    createJoinDefinition(InnerJoin, this -> this.defaultAlias, right, on.apply(this.defaultAlias, right._2))
  }
  def innerJoin[Left](left: (AssociationsFeature[Left], Alias[Left]), right: (AssociationsFeature[_], Alias[_]), on: (Alias[Left], Alias[_]) => SQLSyntax): JoinDefinition[Entity] = {
    createJoinDefinition(InnerJoin, left, right, on.apply(left._2, right._2))
  }

  // ----------------------
  // Left Outer Join Definitions
  // ----------------------

  // using default alias

  def leftJoinWithDefaults(right: AssociationsFeature[_], on: SQLSyntax): JoinDefinition[_] = {
    createJoinDefinition(LeftOuterJoin, this -> this.defaultAlias, right -> right.defaultAlias, on)
  }
  def leftJoinWithDefaults(right: AssociationsFeature[_], on: (Alias[Entity], Alias[Any]) => SQLSyntax): JoinDefinition[_] = {
    createJoinDefinition(LeftOuterJoin, this -> this.defaultAlias, right -> right.defaultAlias, on.apply(this.defaultAlias, right.defaultAlias.asInstanceOf[Alias[Any]]))
  }
  def leftJoinWithDefaults(left: AssociationsFeature[_], right: AssociationsFeature[_], on: (Alias[_], Alias[_]) => SQLSyntax): JoinDefinition[_] = {
    createJoinDefinition(LeftOuterJoin, left -> left.defaultAlias, right -> right.defaultAlias, on.apply(left.defaultAlias, right.defaultAlias))
  }

  // using specified alias

  def leftJoin(right: (AssociationsFeature[_], Alias[_]), on: (Alias[Entity], Alias[_]) => SQLSyntax): JoinDefinition[_] = {
    createJoinDefinition(LeftOuterJoin, this -> this.defaultAlias, right, on.apply(this.defaultAlias, right._2))
  }
  def leftJoin(left: (AssociationsFeature[_], Alias[_]), right: (AssociationsFeature[_], Alias[_]), on: (Alias[_], Alias[_]) => SQLSyntax): JoinDefinition[_] = {
    createJoinDefinition(LeftOuterJoin, left, right, on.apply(left._2, right._2))
  }

  // ----------------------
  // One-to-one
  // ----------------------

  // belongs-to

  def setAsByDefault(extractor: BelongsToExtractor[Entity]): Unit = {
    extractor.byDefault = true
    defaultBelongsToExtractors.add(extractor)
  }

  def belongsTo[A](right: AssociationsFeature[A], merge: (Entity, Option[A]) => Entity): BelongsToAssociation[Entity] = {
    val fk = toDefaultForeignKeyName[A](right)
    belongsToWithJoinCondition[A](right, sqls.eq(this.defaultAlias.field(fk), right.defaultAlias.field(right.primaryKeyFieldName)), merge)
  }

  def belongsToWithJoinCondition[A](right: AssociationsFeature[A], on: SQLSyntax, merge: (Entity, Option[A]) => Entity): BelongsToAssociation[Entity] = {
    val joinDef = leftJoinWithDefaults(right, on)
    val extractor = extractBelongsTo[A](right, toDefaultForeignKeyName[A](right), right.defaultAlias, merge)
    new BelongsToAssociation[Entity](this, unshiftJoinDefinition(joinDef, right.defaultJoinDefinitions.filter(_.enabledEvenIfAssociated)), extractor)
  }

  def belongsToWithFk[A](right: AssociationsFeature[A], fk: String, merge: (Entity, Option[A]) => Entity): BelongsToAssociation[Entity] = {
    belongsToWithFkAndJoinCondition(right, fk, sqls.eq(this.defaultAlias.field(fk), right.defaultAlias.field(right.primaryKeyFieldName)), merge)
  }

  def belongsToWithFkAndJoinCondition[A](right: AssociationsFeature[A], fk: String, on: SQLSyntax, merge: (Entity, Option[A]) => Entity): BelongsToAssociation[Entity] = {
    val joinDef = leftJoinWithDefaults(right, on)
    val extractor = extractBelongsTo[A](right, fk, right.defaultAlias, merge)
    new BelongsToAssociation[Entity](this, unshiftJoinDefinition(joinDef, right.defaultJoinDefinitions.filter(_.enabledEvenIfAssociated)), extractor)
  }

  def belongsToWithAlias[A](right: (AssociationsFeature[A], Alias[A]), merge: (Entity, Option[A]) => Entity): BelongsToAssociation[Entity] = {
    val fk = if (right._1.defaultAlias != right._2) {
      right._2.tableAliasName + "Id"
    } else {
      toDefaultForeignKeyName[A](right._1)
    }
    belongsToWithAliasAndFk(right, fk, merge)
  }

  def belongsToWithAliasAndFk[A](right: (AssociationsFeature[A], Alias[A]), fk: String,
    merge: (Entity, Option[A]) => Entity): BelongsToAssociation[Entity] = {
    belongsToWithAliasAndFkAndJoinCondition(right, fk, sqls.eq(this.defaultAlias.field(fk), right._2.field(right._1.primaryKeyFieldName)), merge)
  }

  def belongsToWithAliasAndFkAndJoinCondition[A](right: (AssociationsFeature[A], Alias[A]), fk: String, on: SQLSyntax,
    merge: (Entity, Option[A]) => Entity): BelongsToAssociation[Entity] = {
    val joinDef = createJoinDefinition(LeftOuterJoin, this -> this.defaultAlias, right, on)
    val extractor = extractBelongsTo[A](right._1, fk, right._2, merge)
    new BelongsToAssociation[Entity](this, unshiftJoinDefinition(joinDef, right._1.defaultJoinDefinitions.filter(_.enabledEvenIfAssociated)), extractor)
  }

  // has-one

  def setAsByDefault(extractor: HasOneExtractor[Entity]): Unit = {
    extractor.byDefault = true
    defaultHasOneExtractors.add(extractor)
  }

  def hasOne[A](right: AssociationsFeature[A], merge: (Entity, Option[A]) => Entity): HasOneAssociation[Entity] = {
    hasOneWithFk[A](right, toDefaultForeignKeyName[Entity](this), merge)
  }

  def hasOneWithJoinCondition[A](right: AssociationsFeature[A], on: SQLSyntax, merge: (Entity, Option[A]) => Entity): HasOneAssociation[Entity] = {
    hasOneWithFkAndJoinCondition(right, toDefaultForeignKeyName[Entity](this), on, merge)
  }

  def hasOneWithFk[A](right: AssociationsFeature[A], fk: String, merge: (Entity, Option[A]) => Entity): HasOneAssociation[Entity] = {
    hasOneWithFkAndJoinCondition(right, fk, sqls.eq(this.defaultAlias.field(this.primaryKeyFieldName), right.defaultAlias.field(fk)), merge)
  }

  def hasOneWithFkAndJoinCondition[A](right: AssociationsFeature[A], fk: String, on: SQLSyntax, merge: (Entity, Option[A]) => Entity): HasOneAssociation[Entity] = {
    val joinDef = leftJoinWithDefaults(right, on)
    val extractor = extractHasOne[A](right, fk, right.defaultAlias, merge)
    new HasOneAssociation[Entity](this, unshiftJoinDefinition(joinDef, right.defaultJoinDefinitions.filter(_.enabledEvenIfAssociated)), extractor)
  }

  def hasOneWithAlias[A](right: (AssociationsFeature[A], Alias[A]), merge: (Entity, Option[A]) => Entity): HasOneAssociation[Entity] = {
    hasOneWithAliasAndFk(right, toDefaultForeignKeyName[Entity](this), merge)
  }

  def hasOneWithAliasAndJoinCondition[A](right: (AssociationsFeature[A], Alias[A]), on: SQLSyntax, merge: (Entity, Option[A]) => Entity): HasOneAssociation[Entity] = {
    hasOneWithAliasAndFkAndJoinCondition(right, toDefaultForeignKeyName[Entity](this), on, merge)
  }

  def hasOneWithAliasAndFk[A](right: (AssociationsFeature[A], Alias[A]), fk: String, merge: (Entity, Option[A]) => Entity): HasOneAssociation[Entity] = {
    hasOneWithAliasAndFkAndJoinCondition(right, fk, sqls.eq(this.defaultAlias.field(this.primaryKeyFieldName), right._2.field(fk)), merge)
  }

  def hasOneWithAliasAndFkAndJoinCondition[A](right: (AssociationsFeature[A], Alias[A]), fk: String, on: SQLSyntax, merge: (Entity, Option[A]) => Entity): HasOneAssociation[Entity] = {
    val joinDef = createJoinDefinition(LeftOuterJoin, this -> this.defaultAlias, right, on)
    val extractor = extractHasOne[A](right._1, fk, right._2, merge)
    new HasOneAssociation[Entity](this, unshiftJoinDefinition(joinDef, right._1.defaultJoinDefinitions.filter(_.enabledEvenIfAssociated)), extractor)
  }

  // ----------------------
  // One-to-many
  // ----------------------

  // has-many

  def setAsByDefault(extractor: HasManyExtractor[Entity]): Unit = {
    extractor.byDefault = true
    defaultOneToManyExtractors.add(extractor)
  }

  def hasMany[M](many: (AssociationsFeature[M], Alias[M]), on: (Alias[Entity], Alias[M]) => SQLSyntax, merge: (Entity, Seq[M]) => Entity): HasManyAssociation[Entity] = {
    val joinDef = leftJoin(this -> this.defaultAlias, many, on.asInstanceOf[(Alias[_], Alias[_]) => SQLSyntax])
    val extractor = extractOneToMany[M](many._1, many._2, merge)
    val definitions = new mutable.LinkedHashSet().+=(joinDef).++(many._1.defaultJoinDefinitions)
    new HasManyAssociation[Entity](this, definitions, extractor)
  }

  def hasManyThrough[M2](through: AssociationsFeature[_], many: AssociationsFeature[M2], merge: (Entity, Seq[M2]) => Entity): HasManyAssociation[Entity] = {
    val throughFk = toDefaultForeignKeyName[Entity](this)
    val manyFk = toDefaultForeignKeyName[M2](many)
    hasManyThrough(
      through = through.asInstanceOf[AssociationsFeature[Any]] -> through.defaultAlias.asInstanceOf[Alias[Any]],
      throughOn = (entity, m1: Alias[_]) => sqls.eq(entity.field(primaryKeyFieldName), m1.field(throughFk)),
      many = many -> many.defaultAlias,
      on = (m1: Alias[_], m2: Alias[M2]) => sqls.eq(m1.field(manyFk), m2.field(many.primaryKeyFieldName)),
      merge = merge)
  }

  def hasManyThroughWithFk[M2](through: AssociationsFeature[_], many: AssociationsFeature[M2], throughFk: String, manyFk: String, merge: (Entity, Seq[M2]) => Entity): HasManyAssociation[Entity] = {
    hasManyThrough(
      through = through.asInstanceOf[AssociationsFeature[Any]] -> through.defaultAlias.asInstanceOf[Alias[Any]],
      throughOn = (entity, m1: Alias[_]) => sqls.eq(entity.field(primaryKeyFieldName), m1.field(throughFk)),
      many = many -> many.defaultAlias,
      on = (m1: Alias[_], m2: Alias[M2]) => sqls.eq(m1.field(manyFk), m2.field(many.primaryKeyFieldName)),
      merge = merge)
  }

  def hasManyThrough[M1, M2](
    through: (AssociationsFeature[M1], Alias[M1]),
    throughOn: (Alias[Entity], Alias[M1]) => SQLSyntax,
    many: (AssociationsFeature[M2], Alias[M2]),
    on: (Alias[M1], Alias[M2]) => SQLSyntax,
    merge: (Entity, Seq[M2]) => Entity): HasManyAssociation[Entity] = {

    val joinDef1 = leftJoin(through, throughOn.asInstanceOf[(Alias[_], Alias[_]) => SQLSyntax])
    val joinDef2 = leftJoin(through, many, on.asInstanceOf[(Alias[_], Alias[_]) => SQLSyntax])
    val definitions = new mutable.LinkedHashSet().+=(joinDef1, joinDef2).++(many._1.defaultJoinDefinitions)
    val extractor = extractOneToMany[M2](many._1, many._2, merge)
    new HasManyAssociation[Entity](this, definitions, extractor)
  }

  // ----------------------
  // Query Builder
  // ----------------------

  /**
   * Returns a select query builder that all associations are joined.
   *
   * @param sql sql object
   * @param belongsToAssociations belongsTo associations
   * @param hasOneAssociations hasOne associations
   * @param hasManyAssociations hasMany associations
   * @return select query builder
   */
  def selectQueryWithAdditionalAssociations(
    sql: SelectSQLBuilder[Entity],
    belongsToAssociations: Seq[BelongsToAssociation[Entity]],
    hasOneAssociations: Seq[HasOneAssociation[Entity]],
    hasManyAssociations: Seq[HasManyAssociation[Entity]]): SelectSQLBuilder[Entity] = {

    val mergedJoinDefinitions = (belongsToAssociations.flatMap(_.joinDefinitions)
      ++ hasOneAssociations.flatMap(_.joinDefinitions)
      ++ hasManyAssociations.flatMap(_.joinDefinitions)
    ).filterNot { df =>
        val currentName = df.rightAlias.tableAliasName
        val sameAsThis = this.defaultAlias.tableAliasName == currentName
        val foundInDefaults = defaultJoinDefinitions.exists(d => d.rightAlias.tableAliasName == currentName)
        foundInDefaults || sameAsThis
      }.foldLeft(mutable.LinkedHashSet[JoinDefinition[_]]()) { (dfs, df) =>
        val currentName = df.rightAlias.tableAliasName
        val duplicated = dfs.exists(d => d.rightAlias.tableAliasName == currentName)
        if (duplicated) dfs else dfs + df
      }

    mergedJoinDefinitions.foldLeft(sql) { (query, join) =>
      // Merge soft deletion or something else default scope condition here
      // (Left one must have the defaultScope in where clause)
      val condition: SQLSyntax = sqls.toAndConditionOpt(
        Some(join.on),
        join.rightMapper.defaultScope(join.rightAlias)
      ).get

      join.joinType match {
        case InnerJoin => query.innerJoin(join.rightMapper.as(join.rightAlias)).on(condition)
        case LeftOuterJoin => query.leftJoin(join.rightMapper.as(join.rightAlias)).on(condition)
        case jt => throw new IllegalStateException(s"Unexpected pattern ${jt}")
      }
    }
  }

  /**
   * Returns th default select query builder for this mapper.
   *
   * @return select query builder
   */
  override def defaultSelectQuery: SelectSQLBuilder[Entity] = {
    // Notice: LinkedHashSet because elements in the order they were inserted
    val definitions = defaultJoinDefinitions.foldLeft(mutable.LinkedHashSet[JoinDefinition[_]]()) { (dfs, df) =>
      val currentName = df.rightAlias.tableAliasName
      val duplicated = dfs.exists(d => d.rightAlias.tableAliasName == currentName)
      if (duplicated) dfs else dfs + df
    }
    definitions.foldLeft(super.defaultSelectQuery) { (query, join) =>
      join.joinType match {
        case InnerJoin if join.enabledByDefault => query.innerJoin(join.rightMapper.as(join.rightAlias)).on(join.on)
        case LeftOuterJoin if join.enabledByDefault => query.leftJoin(join.rightMapper.as(join.rightAlias)).on(join.on)
        case _ => query
      }
    }
  }

  // ----------------------
  // ResultSet Extractor
  // ----------------------

  def extract(sql: SQL[Entity, NoExtractor])(
    implicit includesRepository: IncludesQueryRepository[Entity] = IncludesQueryRepository[Entity]()): SQL[Entity, HasExtractor] = {
    extractWithAssociations(sql, belongsToAssociations, hasOneAssociations, hasManyAssociations)
  }

  /**
   * Creates an extractor for this query.
   *
   * @param sql sql object
   * @param belongsToAssociations belongsTo associations
   * @param hasOneAssociations hasOne associations
   * @param oneToManyAssociations hasMany associations
   * @return sql object
   */
  def extractWithAssociations(
    sql: SQL[Entity, NoExtractor],
    belongsToAssociations: Seq[BelongsToAssociation[Entity]],
    hasOneAssociations: Seq[HasOneAssociation[Entity]],
    oneToManyAssociations: Seq[HasManyAssociation[Entity]])(
      implicit includesRepository: IncludesQueryRepository[Entity] = IncludesQueryRepository[Entity]()): SQL[Entity, HasExtractor] = {

    val enabledJoinDefinitions = defaultJoinDefinitions ++
      belongsToAssociations.map(_.joinDefinitions) ++
      hasOneAssociations.map(_.joinDefinitions) ++
      oneToManyAssociations.map(_.joinDefinitions)

    val enabledOneToManyExtractors = defaultOneToManyExtractors ++ oneToManyAssociations.map(_.extractor)

    if (enabledJoinDefinitions.isEmpty) {
      sql.map(rs => extract(rs, defaultAlias.resultName))

    } else if (enabledOneToManyExtractors.size > 0) {
      val oneExtractedSql: OneToXSQL[Entity, NoExtractor, Entity] = sql.one(rs =>
        extractWithOneToOneTables(rs,
          belongsToAssociations.map(_.extractor).toSet,
          hasOneAssociations.map(_.extractor).toSet))

      if (enabledOneToManyExtractors.size == 1) {
        // one-to-many
        val ex: HasManyExtractor[Entity] = enabledOneToManyExtractors.head
        val mapper = ex.mapper.asInstanceOf[AssociationsFeature[Any]]
        val alias = ex.alias.asInstanceOf[Alias[Any]]
        val sql: OneToManySQL[Entity, _, HasExtractor, Entity] = oneExtractedSql
          .toMany { rs =>
            rs.anyOpt(alias.resultName.field(mapper.primaryKeyFieldName)).map[Any] { _ =>
              includesRepository.putAndReturn(ex, mapper.extract(rs, alias.resultName))
            }
          }.map { case (one, many) => ex.merge(one, many) }
        sql

      } else if (enabledOneToManyExtractors.size == 2) {
        // one-to-manies 2
        val Seq(ex1: HasManyExtractor[Entity], ex2: HasManyExtractor[Entity]) = enabledOneToManyExtractors.toSeq
        val mapper1 = ex1.mapper.asInstanceOf[AssociationsFeature[Any]]
        val mapper2 = ex2.mapper.asInstanceOf[AssociationsFeature[Any]]
        val alias1 = ex1.alias.asInstanceOf[Alias[Any]]
        val alias2 = ex2.alias.asInstanceOf[Alias[Any]]
        val sql: OneToManies2SQL[Entity, _, _, HasExtractor, Entity] = oneExtractedSql
          .toManies(
            to1 = rs => rs.anyOpt(alias1.resultName.field(mapper1.primaryKeyFieldName)).map[Any] { _ =>
              includesRepository.putAndReturn(ex1, mapper1.extract(rs, alias1.resultName))
            },
            to2 = rs => rs.anyOpt(alias2.resultName.field(mapper2.primaryKeyFieldName)).map[Any] { _ =>
              includesRepository.putAndReturn(ex2, mapper2.extract(rs, alias2.resultName))
            }
          ).map { case (one, m1, m2) => ex2.merge(ex1.merge(one, m1), m2) }
        sql

      } else if (enabledOneToManyExtractors.size == 3) {
        // one-to-manies 3
        val Seq(ex1: HasManyExtractor[Entity], ex2: HasManyExtractor[Entity], ex3: HasManyExtractor[Entity]) = enabledOneToManyExtractors.toSeq
        val mapper1 = ex1.mapper.asInstanceOf[AssociationsFeature[Any]]
        val mapper2 = ex2.mapper.asInstanceOf[AssociationsFeature[Any]]
        val mapper3 = ex3.mapper.asInstanceOf[AssociationsFeature[Any]]
        val alias1 = ex1.alias.asInstanceOf[Alias[Any]]
        val alias2 = ex2.alias.asInstanceOf[Alias[Any]]
        val alias3 = ex3.alias.asInstanceOf[Alias[Any]]
        val sql: OneToManies3SQL[Entity, _, _, _, HasExtractor, Entity] = oneExtractedSql
          .toManies(
            to1 = rs => rs.anyOpt(alias1.resultName.field(mapper1.primaryKeyFieldName))
              .map[Any] { _ => includesRepository.putAndReturn(ex1, mapper1.extract(rs, alias1.resultName)) },
            to2 = rs => rs.anyOpt(alias2.resultName.field(mapper2.primaryKeyFieldName))
              .map[Any] { _ => includesRepository.putAndReturn(ex2, mapper2.extract(rs, alias2.resultName)) },
            to3 = rs => rs.anyOpt(alias3.resultName.field(mapper3.primaryKeyFieldName))
              .map[Any] { _ => includesRepository.putAndReturn(ex3, mapper3.extract(rs, alias3.resultName)) }

          ).map { case (one, m1, m2, m3) => ex3.merge(ex2.merge(ex1.merge(one, m1), m2), m3) }
        sql

      } else if (enabledOneToManyExtractors.size == 4) {
        // one-to-manies 4
        val Seq(ex1: HasManyExtractor[Entity], ex2: HasManyExtractor[Entity], ex3: HasManyExtractor[Entity], ex4: HasManyExtractor[Entity]) = enabledOneToManyExtractors.toSeq
        val mapper1 = ex1.mapper.asInstanceOf[AssociationsFeature[Any]]
        val mapper2 = ex2.mapper.asInstanceOf[AssociationsFeature[Any]]
        val mapper3 = ex3.mapper.asInstanceOf[AssociationsFeature[Any]]
        val mapper4 = ex4.mapper.asInstanceOf[AssociationsFeature[Any]]
        val alias1 = ex1.alias.asInstanceOf[Alias[Any]]
        val alias2 = ex2.alias.asInstanceOf[Alias[Any]]
        val alias3 = ex3.alias.asInstanceOf[Alias[Any]]
        val alias4 = ex4.alias.asInstanceOf[Alias[Any]]
        val sql: OneToManies4SQL[Entity, _, _, _, _, HasExtractor, Entity] = oneExtractedSql
          .toManies(
            to1 = rs => rs.anyOpt(alias1.resultName.field(mapper1.primaryKeyFieldName))
              .map[Any](_ => includesRepository.putAndReturn(ex1, mapper1.extract(rs, alias1.resultName))),
            to2 = rs => rs.anyOpt(alias2.resultName.field(mapper2.primaryKeyFieldName))
              .map[Any](_ => includesRepository.putAndReturn(ex2, mapper2.extract(rs, alias2.resultName))),
            to3 = rs => rs.anyOpt(alias3.resultName.field(mapper3.primaryKeyFieldName))
              .map[Any](_ => includesRepository.putAndReturn(ex3, mapper3.extract(rs, alias3.resultName))),
            to4 = rs => rs.anyOpt(alias4.resultName.field(mapper4.primaryKeyFieldName))
              .map[Any](_ => includesRepository.putAndReturn(ex4, mapper4.extract(rs, alias4.resultName)))

          ).map { case (one, m1, m2, m3, m4) => ex4.merge(ex3.merge(ex2.merge(ex1.merge(one, m1), m2), m3), m4) }
        sql

      } else if (enabledOneToManyExtractors.size == 5) {
        // one-to-manies 5
        val Seq(ex1: HasManyExtractor[Entity], ex2: HasManyExtractor[Entity], ex3: HasManyExtractor[Entity], ex4: HasManyExtractor[Entity], ex5: HasManyExtractor[Entity]) = enabledOneToManyExtractors.toSeq
        val mapper1 = ex1.mapper.asInstanceOf[AssociationsFeature[Any]]
        val mapper2 = ex2.mapper.asInstanceOf[AssociationsFeature[Any]]
        val mapper3 = ex3.mapper.asInstanceOf[AssociationsFeature[Any]]
        val mapper4 = ex4.mapper.asInstanceOf[AssociationsFeature[Any]]
        val mapper5 = ex5.mapper.asInstanceOf[AssociationsFeature[Any]]
        val alias1 = ex1.alias.asInstanceOf[Alias[Any]]
        val alias2 = ex2.alias.asInstanceOf[Alias[Any]]
        val alias3 = ex3.alias.asInstanceOf[Alias[Any]]
        val alias4 = ex4.alias.asInstanceOf[Alias[Any]]
        val alias5 = ex5.alias.asInstanceOf[Alias[Any]]
        val sql: OneToManies5SQL[Entity, _, _, _, _, _, HasExtractor, Entity] = oneExtractedSql
          .toManies(
            to1 = rs => rs.anyOpt(alias1.resultName.field(mapper1.primaryKeyFieldName))
              .map[Any](_ => includesRepository.putAndReturn(ex1, mapper1.extract(rs, alias1.resultName))),
            to2 = rs => rs.anyOpt(alias2.resultName.field(mapper2.primaryKeyFieldName))
              .map[Any](_ => includesRepository.putAndReturn(ex2, mapper2.extract(rs, alias2.resultName))),
            to3 = rs => rs.anyOpt(alias3.resultName.field(mapper3.primaryKeyFieldName))
              .map[Any](_ => includesRepository.putAndReturn(ex3, mapper3.extract(rs, alias3.resultName))),
            to4 = rs => rs.anyOpt(alias4.resultName.field(mapper4.primaryKeyFieldName))
              .map[Any](_ => includesRepository.putAndReturn(ex4, mapper4.extract(rs, alias4.resultName))),
            to5 = rs => rs.anyOpt(alias5.resultName.field(mapper5.primaryKeyFieldName))
              .map[Any](_ => includesRepository.putAndReturn(ex5, mapper5.extract(rs, alias5.resultName)))

          ).map { case (one, m1, m2, m3, m4, m5) => ex5.merge(ex4.merge(ex3.merge(ex2.merge(ex1.merge(one, m1), m2), m3), m4), m5) }
        sql

      } else {
        throw new IllegalStateException(s"Unsupported one-to-manies settings. (max: 5, actual: ${defaultOneToManyExtractors.size})")
      }

    } else {
      // several one-to-one and so on
      sql.map(rs => extractWithOneToOneTables(rs,
        belongsToAssociations.map(_.extractor).toSet,
        hasOneAssociations.map(_.extractor).toSet))
    }
  }

  /**
   * Extracts entity with one-to-one tables.
   *
   * @param rs result set
   * @param belongsToExtractors belongsTo extractors
   * @param hasOneExtractors hasOne extractors
   * @return entity
   */
  def extractWithOneToOneTables(
    rs: WrappedResultSet,
    belongsToExtractors: Set[BelongsToExtractor[Entity]],
    hasOneExtractors: Set[HasOneExtractor[Entity]])(implicit includesRepository: IncludesQueryRepository[Entity]): Entity = {

    val allBelongsTo = defaultBelongsToExtractors ++ belongsToExtractors
    val withBelongsTo = allBelongsTo.foldLeft(extract(rs, defaultAlias.resultName)) {
      case (entity, extractor) =>
        val mapper = extractor.mapper.asInstanceOf[AssociationsFeature[Any]]
        val toOne: Option[_] = rs.anyOpt(defaultAlias.resultName.field(extractor.fk))
          .flatMap { _ =>
            try {
              val entity = mapper.extract(rs, extractor.alias.resultName.asInstanceOf[ResultName[Any]])
              Some(includesRepository.putAndReturn(extractor, entity))
            } catch {
              case e: ResultSetExtractorException =>
                // Although fk in the left entity is available 
                // but the right entity is absent when the right one is deleted softly
                logger.debug(s"The right entity is absent. It may be deleted softly. (fk: ${extractor.fk})")
                None
            }
          }
        extractor.merge(entity, toOne)
    }
    val allHasOne = defaultHasOneExtractors ++ hasOneExtractors
    val withAssociations = allHasOne.foldLeft(withBelongsTo) {
      case (entity, extractor) =>
        val mapper = extractor.mapper.asInstanceOf[AssociationsFeature[Any]]
        val toOne: Option[_] = rs.anyOpt(extractor.alias.resultName.field(extractor.fk))
          .flatMap { _ =>
            try {
              val entity = mapper.extract(rs, extractor.alias.resultName.asInstanceOf[ResultName[Any]])
              Some(includesRepository.putAndReturn(extractor, entity))
            } catch {
              case e: ResultSetExtractorException =>
                // Although fk in the left entity is available
                // but the right entity is absent when the right one is deleted softly
                logger.debug(s"The right entity is absent. It may be deleted softly. (fk: ${extractor.fk})")
                None
            }
          }
        extractor.merge(entity, toOne)
    }
    withAssociations
  }

  // -----------------------------------------
  // One to One Relation
  // -----------------------------------------

  val defaultBelongsToExtractors = new mutable.LinkedHashSet[BelongsToExtractor[Entity]]()

  def extractBelongsTo[That](mapper: AssociationsFeature[That], fk: String, alias: Alias[That], merge: (Entity, Option[That]) => Entity, includesMerge: (Seq[Entity], Seq[That]) => Seq[Entity] = defaultIncludesMerge[Entity, That]): BelongsToExtractor[Entity] = {
    BelongsToExtractor[Entity](mapper, fk, alias, merge.asInstanceOf[(Entity, Option[Any]) => Entity], includesMerge.asInstanceOf[(Seq[Entity], Seq[Any]) => Seq[Entity]])
  }

  val defaultHasOneExtractors = new mutable.LinkedHashSet[HasOneExtractor[Entity]]()

  def extractHasOne[That](mapper: AssociationsFeature[That], fk: String, alias: Alias[That], merge: (Entity, Option[That]) => Entity, includesMerge: (Seq[Entity], Seq[That]) => Seq[Entity] = defaultIncludesMerge[Entity, That]): HasOneExtractor[Entity] = {
    HasOneExtractor[Entity](mapper, fk, alias, merge.asInstanceOf[(Entity, Option[Any]) => Entity], includesMerge.asInstanceOf[(Seq[Entity], Seq[Any]) => Seq[Entity]])
  }

  // -----------------------------------------
  // One to Many Relation
  // -----------------------------------------

  val defaultOneToManyExtractors = new mutable.LinkedHashSet[HasManyExtractor[Entity]]()

  /**
   * One-to-Many relationship definition.
   *
   * {{{
   * object Member extends RelationshipFeature[Member] {
   *   oneToMany[Group](
   *     mapper = Group,
   *     merge = (m, c) => m.copy(company = c)
   *   )
   * }
   * }}}
   */
  def extractOneToManyWithDefaults[M1](mapper: AssociationsFeature[M1], merge: (Entity, Seq[M1]) => Entity, includesMerge: (Seq[Entity], Seq[M1]) => Seq[Entity] = defaultIncludesMerge[Entity, M1]): HasManyExtractor[Entity] = {
    extractOneToMany[M1](mapper, mapper.defaultAlias, merge, includesMerge)
  }

  def extractOneToMany[M1](mapper: AssociationsFeature[M1], alias: Alias[M1], merge: (Entity, Seq[M1]) => Entity, includesMerge: (Seq[Entity], Seq[M1]) => Seq[Entity] = defaultIncludesMerge[Entity, M1]): HasManyExtractor[Entity] = {
    if (defaultOneToManyExtractors.size > 5) {
      throw new IllegalStateException("Skinny ORM doesn't support more than 5 one-to-many tables.")
    }
    HasManyExtractor[Entity](
      mapper = mapper,
      alias = alias,
      merge = merge.asInstanceOf[(Entity, Seq[Any]) => Entity],
      includesMerge = includesMerge.asInstanceOf[(Seq[Entity], Seq[Any]) => Seq[Entity]])
  }

  /**
   * Expects mapper's name + "Id" by default.
   *
   * @param mapper mapper
   * @tparam A enitty type
   * @return fk name
   */
  protected def toDefaultForeignKeyName[A](mapper: AssociationsFeature[A]): String = {
    val name = JavaReflectAPI.classSimpleName(mapper).replaceFirst("\\$$", "") + "Id"
    name.head.toString.toLowerCase + name.tail
  }

}
