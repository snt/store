package controllers

import play.api.i18n.{Lang, Messages}
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import models._
import play.api.data.Form
import controllers.I18n.I18nAware
import play.api.mvc.Controller
import play.api.db.DB
import play.api.i18n.Messages
import play.api.Play.current
import scala.collection.immutable.LongMap
import helpers.NotificationMail

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

  def index = isAuthenticated { implicit login => forAdmin { implicit request =>
    DB.withConnection { implicit conn =>
      val list = TransactionSummary.list(login.siteUser)
      Ok(
        views.html.admin.transactionMaintenance(
          list,
          changeStatusForm, statusDropDown,
          list.foldLeft(LongMap[Form[ChangeShippingInfo]]()) {
            (map, e) => map.updated(e.transactionSiteId, entryShippingInfoForm)
          },
          Transporter.tableForDropDown,
          Transporter.listWithName.foldLeft(LongMap[String]()) {
            (sum, e) => sum.updated(e._1.id.get, e._2.map(_.transporterName).getOrElse("-"))
          }
        )
      )
    }
  }}

  def setStatus = isAuthenticated { implicit login => forAdmin { implicit request =>
    changeStatusForm.bindFromRequest.fold(
      formWithErrors => {
        logger.error("Validation error in TransactionMaintenance.setStatus. " + formWithErrors)
        DB.withConnection { implicit conn =>
          val list = TransactionSummary.list(
            if (login.isAdmin) None else login.siteUser
          )

          BadRequest(
            views.html.admin.transactionMaintenance(
              list,
              changeStatusForm, statusDropDown,
              list.foldLeft(LongMap[Form[ChangeShippingInfo]]()) {
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
          Redirect(routes.TransactionMaintenance.index)
        }
      }
    )
  }}

  def detail(tranSiteId: Long) = isAuthenticated { implicit login => forAdmin { implicit request =>
    DB.withConnection { implicit conn =>
      val entry = TransactionSummary.get(login.siteUser, tranSiteId).get
      Ok(
        views.html.admin.transactionDetail(
          entry,
          TransactionDetail.show(tranSiteId, LocaleInfo.byLang(lang), login.siteUser),
          TransactionMaintenance.changeStatusForm, TransactionMaintenance.statusDropDown,
          LongMap[Form[ChangeShippingInfo]](entry.transactionSiteId -> TransactionMaintenance.entryShippingInfoForm),
          Transporter.tableForDropDown,
          Transporter.listWithName.foldLeft(LongMap[String]()) {
            (sum, e) => sum.updated(e._1.id.get, e._2.map(_.transporterName).getOrElse("-"))
          }
        )
      )
    }
  }}

  def entryShippingInfo(tranId: Long, tranSiteId: Long) = isAuthenticated { implicit login => forAdmin { implicit request =>
    entryShippingInfoForm.bindFromRequest.fold(
      formWithErrors => {
        logger.error("Validation error in TransactionMaintenance.entryShippingInfo. " + formWithErrors)
        DB.withTransaction { implicit conn =>
          val list = TransactionSummary.list(
            if (login.isAdmin) None else login.siteUser
          )

          BadRequest(
            views.html.admin.transactionMaintenance(
              list,
              changeStatusForm, statusDropDown,
              list.foldLeft(LongMap[Form[ChangeShippingInfo]]()) {
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
          Redirect(routes.TransactionMaintenance.index)
        }
      }
    )
  }}

  def cancelShipping(tranId: Long, tranSiteId: Long) = isAuthenticated { implicit login => forAdmin { implicit request =>
    DB.withTransaction { implicit conn =>
      TransactionShipStatus.update(login.siteUser, tranSiteId, TransactionStatus.CANCELED)
      val status = TransactionShipStatus.byTransactionSiteId(tranSiteId)
      if (! status.mailSent) {
        TransactionShipStatus.mailSent(tranSiteId)
        sendCancelMail(tranId, tranSiteId, LocaleInfo.getDefault, status)
      }
      Redirect(routes.TransactionMaintenance.index)
    }
  }}

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
}
