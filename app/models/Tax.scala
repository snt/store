package models

import anorm._
import anorm.{NotAssigned, Pk}
import anorm.SqlParser
import play.api.Play.current
import play.api.db._
import scala.language.postfixOps
import play.api.i18n.{Messages, Lang}
import java.sql.Connection
import math.BigDecimal.RoundingMode

case class Tax(id: Pk[Long] = NotAssigned) extends NotNull

case class TaxHistory(
  id: Pk[Long] = NotAssigned, taxId: Long, taxType: TaxType, rate: BigDecimal, validUntil: Long
) extends NotNull {
  lazy val realRate = rate / BigDecimal(100)

  def taxAmount(amount: BigDecimal): BigDecimal = (taxType match {
      case TaxType.OUTER_TAX => amount * realRate
      case TaxType.INNER_TAX => realRate * amount / (realRate + 1)
      case TaxType.NON_TAX => BigDecimal(0)
    }).setScale(0, RoundingMode.DOWN)
}

case class TaxName(id: Pk[Long] = NotAssigned, taxId: Long, locale: LocaleInfo, taxName: String) extends NotNull

object Tax {
  val simple = {
    SqlParser.get[Pk[Long]]("tax.tax_id") map {
      case id => Tax(id)
    }
  }

  val withName = Tax.simple ~ TaxName.simple map {
    case tax~name => (tax, name)
  }

  def apply(id: Long)(implicit conn: Connection): Tax =
    SQL(
      "select * from tax where tax_id = {id}"
    ).on(
      'id -> id
    ).as(
      simple.single
    )

  def createNew(implicit conn: Connection): Tax = {
    SQL("insert into tax (tax_id) values ((select nextval('tax_seq')))").executeUpdate()

    val taxId = SQL("select currval('tax_seq')").as(SqlParser.scalar[Long].single)

    Tax(Id(taxId))
  }

  def list(implicit conn: Connection): Seq[Tax] = SQL(
    "select * from tax order by tax_id"
  ).as(Tax.simple *)

  val allTaxType = classOf[TaxType].getEnumConstants().toList

  def taxTypeTable(implicit lang: Lang): Seq[(String, String)] = {
    val locale = LocaleInfo.byLang(lang)

    allTaxType.map {
      e => e.ordinal.toString -> Messages("tax." + e)
    }
  }

  def tableForDropDown(implicit lang: Lang, conn: Connection): Seq[(String, String)] = {
    val locale = LocaleInfo.byLang(lang)

    SQL(
      """
      select * from tax
      inner join tax_name on tax.tax_id = tax_name.tax_id
      where tax_name.locale_id = {localeId}
      order by tax_name.tax_name
      """
    ).on(
      'localeId -> locale.id
    ).as(
      withName *
    ).map {
      e => e._1.id.get.toString -> e._2.taxName
    }

    

  }
}

object TaxName {
  val simple = {
    SqlParser.get[Pk[Long]]("tax_name.tax_name_id") ~
    SqlParser.get[Long]("tax_name.tax_id") ~
    SqlParser.get[Long]("tax_name.locale_id") ~
    SqlParser.get[String]("tax_name.tax_name") map {
      case id~taxId~localeId~taxName =>
        TaxName(id, taxId, LocaleInfo(localeId), taxName)
    }
  }

  def createNew(tax: Tax, locale: LocaleInfo, name: String)(implicit conn: Connection): TaxName = {
    SQL(
      """
      insert into tax_name (tax_name_id, tax_id, locale_id, tax_name)
      values (
        (select nextval('tax_name_seq')),
        {taxId}, {localeId}, {taxName}
      )
      """
    ).on(
      'taxId -> tax.id.get,
      'localeId -> locale.id,
      'taxName -> name
    ).executeUpdate()

    val id = SQL("select currval('tax_name_seq')").as(SqlParser.scalar[Long].single)

    TaxName(Id(id), tax.id.get, locale, name)
  }
}

object TaxHistory {
  val simple = {
    SqlParser.get[Pk[Long]]("tax_history.tax_history_id") ~
    SqlParser.get[Long]("tax_history.tax_id") ~
    SqlParser.get[Int]("tax_history.tax_type") ~
    SqlParser.get[java.math.BigDecimal]("tax_history.rate") ~
    SqlParser.get[java.util.Date]("tax_history.valid_until") map {
      case id~taxId~taxType~rate~validUntil =>
        TaxHistory(id, taxId, TaxType.byIndex(taxType), rate, validUntil.getTime)
    }
  }

  def createNew(
    tax: Tax, taxType: TaxType, rate: BigDecimal, validUntil: Long
  )(implicit conn: Connection) : TaxHistory = {
    SQL(
      """
      insert into tax_history (tax_history_id, tax_id, tax_type, rate, valid_until)
      values ((select nextval('tax_history_seq')), {taxId}, {taxType}, {rate}, {validUntil})
      """
    ).on(
      'taxId -> tax.id.get,
      'taxType -> taxType.ordinal,
      'rate -> rate.bigDecimal,
      'validUntil -> new java.sql.Timestamp(validUntil)
    ).executeUpdate()

    val id = SQL("select currval('tax_history_seq')").as(SqlParser.scalar[Long].single)

    TaxHistory(Id(id), tax.id.get, taxType, rate, validUntil)
  }

  def at(
    taxId: Long, now: Long = System.currentTimeMillis
  )(implicit conn: Connection): TaxHistory = SQL(
    """
    select * from tax_history
    where tax_id = {taxId}
    and {now} < valid_until
    order by valid_until
    limit 1
    """
  ).on(
    'taxId -> taxId,
    'now -> new java.sql.Timestamp(now)
  ).as(
    TaxHistory.simple.single
  )
}
