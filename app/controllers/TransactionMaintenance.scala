package controllers

import scala.concurrent.ExecutionContext.Implicits.global
import play.api.i18n.{Lang, Messages}
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import models._
import play.api.data.Form
import controllers.I18n.I18nAware
import play.api.mvc.{ResponseHeader, Result, Controller}
import play.api.db.DB
import play.api.i18n.Messages
import play.api.Play.current
import scala.collection.immutable.LongMap
import helpers.{Csv, NotificationMail}
import java.io.{StringWriter, ByteArrayInputStream}
import play.api.libs.iteratee.Enumerator
import play.api.libs.MimeTypes
import play.api.http.ContentTypes
import views.csv.{TransactionDetailBodyCsv, TransactionDetailCsv}

object TransactionMaintenance extends Controller with I18nAware with NeedLogin with HasLogger {
  val changeStatusForm = Form(
    mapping(
      "transactionSiteId" -> longNumber,
      "status" -> number
    )(ChangeTransactionStatus.apply)(ChangeTransactionStatus.unapply)
  )

  def entryShippingInfoForm = Form(
    mapping(
      "transporterId" -> longNumber,
      "slipCode" -> text.verifying(nonEmpty, maxLength(128))
    )(ChangeShippingInfo.apply)(ChangeShippingInfo.unapply)
  )

  def statusDropDown(implicit lang: Lang): Seq[(String, String)] =
    classOf[TransactionStatus].getEnumConstants.foldLeft(List[(String, String)]()) {
      (list, e) => (e.ordinal.toString, Messages("transaction.status." + e.toString)) :: list
    }.reverse

  def index(
    page: Int, pageSize: Int, orderBySpec: String
  ) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      DB.withConnection { implicit conn =>
        val pagedRecords = TransactionSummary.list(
          siteId = login.siteUser.map(_.siteId),
          page = page, pageSize = pageSize, orderBy = OrderBy(orderBySpec)
        )
        Ok(
          views.html.admin.transactionMaintenance(
            pagedRecords,
            changeStatusForm, statusDropDown,
            pagedRecords.records.foldLeft(LongMap[Form[ChangeShippingInfo]]()) {
              (map, e) => map.updated(e.transactionSiteId, entryShippingInfoForm)
            },
            Transporter.tableForDropDown,
            Transporter.listWithName.foldLeft(LongMap[String]()) {
              (sum, e) => sum.updated(e._1.id.get, e._2.map(_.transporterName).getOrElse("-"))
            }
          )
        )
      }
    }
  }

  def setStatus = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      changeStatusForm.bindFromRequest.fold(
        formWithErrors => {
          logger.error("Validation error in TransactionMaintenance.setStatus. " + formWithErrors)
          DB.withConnection { implicit conn =>
            val pagedRecords = TransactionSummary.list(
              login.siteUser.map(_.siteId)
            )

            BadRequest(
              views.html.admin.transactionMaintenance(
                pagedRecords,
                changeStatusForm, statusDropDown,
                pagedRecords.records.foldLeft(LongMap[Form[ChangeShippingInfo]]()) {
                  (map, e) => map.updated(e.transactionSiteId, entryShippingInfoForm)
                },
                Transporter.tableForDropDown,
                Transporter.listWithName.foldLeft(LongMap[String]()) {
                  (sum, e) => sum.updated(e._1.id.get, e._2.map(_.transporterName).getOrElse("-"))
                }
              )
            )
          }
        },
        newStatus => {
          DB.withConnection { implicit conn =>
            newStatus.save(login.siteUser)
            Redirect(routes.TransactionMaintenance.index())
          }
        }
      )
    }
  }

  def detail(tranSiteId: Long) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      DB.withConnection { implicit conn =>
        val entry = TransactionSummary.get(login.siteUser.map(_.siteId), tranSiteId).get
        val boxNameByItemSize = TransactionLogShipping.listBySite(tranSiteId).foldLeft(LongMap.empty[String]) {
          (sum, e) => (sum + (e.itemClass -> e.boxName))
        }
        Ok(
          views.html.admin.transactionDetail(
            entry,
            TransactionDetail.show(tranSiteId, LocaleInfo.getDefault, login.siteUser),
            TransactionMaintenance.changeStatusForm, TransactionMaintenance.statusDropDown,
            LongMap[Form[ChangeShippingInfo]](entry.transactionSiteId -> TransactionMaintenance.entryShippingInfoForm),
            Transporter.tableForDropDown,
            Transporter.listWithName.foldLeft(LongMap[String]()) {
              (sum, e) => sum.updated(e._1.id.get, e._2.map(_.transporterName).getOrElse("-"))
            },
            boxNameByItemSize
          )
        )
      }
    }
  }

  def entryShippingInfo(tranId: Long, tranSiteId: Long) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      entryShippingInfoForm.bindFromRequest.fold(
        formWithErrors => {
          logger.error("Validation error in TransactionMaintenance.entryShippingInfo. " + formWithErrors)
          DB.withTransaction { implicit conn =>
            val pagedRecords = TransactionSummary.list(
              login.siteUser.map(_.siteId)
            )

            BadRequest(
              views.html.admin.transactionMaintenance(
                pagedRecords,
                changeStatusForm, statusDropDown,
                pagedRecords.records.foldLeft(LongMap[Form[ChangeShippingInfo]]()) {
                  (map, e) => map.updated(e.transactionSiteId, entryShippingInfoForm)
                }.updated(tranSiteId, formWithErrors),
                Transporter.tableForDropDown,
                Transporter.listWithName.foldLeft(LongMap[String]()) {
                  (sum, e) => sum.updated(e._1.id.get, e._2.map(_.transporterName).getOrElse("-"))
                }
              )
            )
          }
        },
        newShippingInfo => {
          DB.withConnection { implicit conn =>
            newShippingInfo.save(login.siteUser, tranSiteId) {
              val status = TransactionShipStatus.byTransactionSiteId(tranSiteId)
              sendNotificationMail(tranId, tranSiteId, newShippingInfo, LocaleInfo.getDefault, status)
            }
            Redirect(routes.TransactionMaintenance.index())
          }
        }
      )
    }
  }

  def cancelShipping(tranId: Long, tranSiteId: Long) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      DB.withTransaction { implicit conn =>
        TransactionShipStatus.update(login.siteUser, tranSiteId, TransactionStatus.CANCELED)
        val status = TransactionShipStatus.byTransactionSiteId(tranSiteId)
        if (! status.mailSent) {
          TransactionShipStatus.mailSent(tranSiteId)
          sendCancelMail(tranId, tranSiteId, LocaleInfo.getDefault, status)
        }
        Redirect(routes.TransactionMaintenance.index())
      }
    }
  }

  def sendNotificationMail(
    tranId: Long, tranSiteId: Long, info: ChangeShippingInfo, locale: LocaleInfo, status: TransactionShipStatus
  )(implicit loginSession: LoginSession) {
    DB.withConnection { implicit conn =>
      val persister = new TransactionPersister()
      val tran = persister.load(tranId, locale)
      val address = Address.byId(tran.shippingTable.head._2.head.addressId)
      val siteId = TransactionLogSite.byId(tranSiteId).siteId
      val transporters = Transporter.mapWithName(locale)
      NotificationMail.shipCompleted(loginSession, siteId, tran, address, status, transporters)
    }
  }

  def sendCancelMail(
    tranId: Long, tranSiteId: Long, locale: LocaleInfo, status: TransactionShipStatus
  )(implicit loginSession: LoginSession) {
    DB.withConnection { implicit conn =>
      val persister = new TransactionPersister()
      val tran = persister.load(tranId, locale)
      val address = Address.byId(tran.shippingTable.head._2.head.addressId)
      val siteId = TransactionLogSite.byId(tranSiteId).siteId
      val transporters = Transporter.mapWithName(locale)
      NotificationMail.shipCanceled(loginSession, siteId, tran, address, status, transporters)
    }
  }

  def downloadCsv(tranId: Long, tranSiteId: Long) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      implicit val cs = play.api.mvc.Codec.javaSupported("Windows-31j")
      val fileName = "tranDetail" + tranId + "-" + tranSiteId + ".csv"

      Ok(
        createCsv(tranId, tranSiteId)
      ).as(
        "text/csv charset=Shift_JIS"
      ).withHeaders(
        CONTENT_DISPOSITION -> ("""attachment; filename="%s"""".format(fileName))
      )
    }
  }

  def createCsv(tranId: Long, tranSiteId: Long)(
    implicit lang: Lang,
    loginSession: LoginSession
  ): String = {
    val (entry, details, shipping) = DB.withConnection { implicit conn =>
      (
        TransactionSummary.get(loginSession.siteUser.map(_.siteId), tranSiteId).get,
        TransactionDetail.show(tranSiteId, LocaleInfo.byLang(lang), loginSession.siteUser),
        TransactionLogShipping.listBySite(tranSiteId)
      )
    }
    val writer = new StringWriter
    val csvWriter = TransactionDetailCsv.instance.createWriter(writer)
    val csvDetail = new TransactionDetailBodyCsv(csvWriter)
    details.foreach {
      detail => csvDetail.print(tranId, entry, detail)
    }
    shipping.foreach {
      s => csvDetail.printShipping(tranId, entry, s)
    }
    writer.toString
  }
}
