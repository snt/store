package controllers

import helpers.Cache
import play.api.data.Form
import controllers.I18n.I18nAware
import play.api.mvc.Controller
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import models._
import play.api.i18n.{Lang, Messages}
import play.api.db.DB
import play.api.Play.current
import models.CreateItem
import org.postgresql.util.PSQLException
import java.sql.SQLException
import org.joda.time.DateTime
import helpers.QueryString
import play.api.Play

class ChangeItem(
  val id: Long,
  val siteMap: Map[Long, Site],
  val langTable: Seq[(String, String)],
  val itemNameTableForm: Form[ChangeItemNameTable],
  val newItemNameForm: Form[ChangeItemName],
  val siteNameTable: Seq[(String, String)],
  val siteItemTable: Seq[(Site, SiteItem)],
  val newSiteItemForm: Form[ChangeSiteItem],
  val updateCategoryForm: Form[ChangeItemCategory],
  val categoryTable: Seq[(String, String)],
  val itemDescriptionTableForm: Form[ChangeItemDescriptionTable],
  val newItemDescriptionForm: Form[ChangeItemDescription],
  val itemPriceTableForm: Form[ChangeItemPriceTable],
  val newItemPriceForm: Form[ChangeItemPrice],
  val taxTable: Seq[(String, String)],
  val currencyTable: Seq[(String, String)],
  val itemInSiteTable: Seq[(String, String)],
  val itemMetadataTableForm: Form[ChangeItemMetadataTable],
  val newItemMetadataForm: Form[ChangeItemMetadata],
  val siteItemMetadataTableForm: Form[ChangeSiteItemMetadataTable],
  val newSiteItemMetadataForm: Form[ChangeSiteItemMetadata],
  val siteItemTextMetadataTableForm: Form[ChangeSiteItemTextMetadataTable],
  val newSiteItemTextMetadataForm: Form[ChangeSiteItemTextMetadata],
  val itemTextMetadataTableForm: Form[ChangeItemTextMetadataTable],
  val newItemTextMetadataForm: Form[ChangeItemTextMetadata],
  val attachmentNames: Map[Int, String],
  val couponForm: Form[ChangeCoupon],
  val newSupplementalCategoryForm: Form[ChangeSupplementalCategory],
  val supplementalCategories: Seq[(Long, String)]
)

object ItemMaintenance extends Controller with I18nAware with NeedLogin with HasLogger {
  def createChangeItem(
    id: Long,
    login: LoginSession,
    lang: Lang
  )(
    siteMap: Map[Long, Site] = ItemMaintenance.siteListAsMap,
    langTable: Seq[(String, String)] = LocaleInfo.localeTable,
    itemNameTableForm: Form[ChangeItemNameTable] = ItemMaintenance.createItemNameTable(id),
    newItemNameForm: Form[ChangeItemName] = ItemMaintenance.addItemNameForm,
    siteNameTable: Seq[(String, String)] = ItemMaintenance.createSiteTable(login),
    siteItemTable: Seq[(Site, SiteItem)] = ItemMaintenance.createSiteItemTable(id),
    newSiteItemForm: Form[ChangeSiteItem] = ItemMaintenance.addSiteItemForm,
    updateCategoryForm: Form[ChangeItemCategory] = ItemMaintenance.createItemCategoryForm(id),
    categoryTable: Seq[(String, String)] = ItemMaintenance.createCategoryTable(lang),
    itemDescriptionTableForm: Form[ChangeItemDescriptionTable] = ItemMaintenance.createItemDescriptionTable(id),
    newItemDescriptionForm: Form[ChangeItemDescription] = ItemMaintenance.addItemDescriptionForm,
    itemPriceTableForm: Form[ChangeItemPriceTable] = ItemMaintenance.createItemPriceTable(id),
    newItemPriceForm: Form[ChangeItemPrice] = ItemMaintenance.addItemPriceForm,
    taxTable: Seq[(String, String)] = ItemMaintenance.taxTable(lang),
    currencyTable: Seq[(String, String)] = ItemMaintenance.currencyTable,
    itemInSiteTable: Seq[(String, String)] = ItemMaintenance.createSiteTable(id)(login),
    itemMetadataTableForm: Form[ChangeItemMetadataTable] = ItemMaintenance.createItemMetadataTable(id),
    newItemMetadataForm: Form[ChangeItemMetadata] = ItemMaintenance.addItemMetadataForm,
    siteItemMetadataTableForm: Form[ChangeSiteItemMetadataTable] = ItemMaintenance.createSiteItemMetadataTable(id),
    newSiteItemMetadataForm: Form[ChangeSiteItemMetadata] = ItemMaintenance.addSiteItemMetadataForm,
    siteItemTextMetadataTableForm: Form[ChangeSiteItemTextMetadataTable] = ItemMaintenance.createSiteItemTextMetadataTable(id),
    newSiteItemTextMetadataForm: Form[ChangeSiteItemTextMetadata] = ItemMaintenance.addSiteItemTextMetadataForm,
    itemTextMetadataTableForm: Form[ChangeItemTextMetadataTable] = ItemMaintenance.createItemTextMetadataTable(id),
    newItemTextMetadataForm: Form[ChangeItemTextMetadata] = ItemMaintenance.addItemTextMetadataForm,
    attachmentNames: Map[Int, String] = ItemPictures.retrieveAttachmentNames(id),
    couponForm: Form[ChangeCoupon] = ItemMaintenance.createCouponForm(ItemId(id)),
    newSupplementalCategoryForm: Form[ChangeSupplementalCategory] = ItemMaintenance.addSupplementalCategoryForm,
    supplementalCategories: Seq[(Long, String)] = ItemMaintenance.supplementalCategories(ItemId(id), lang)
  ) = new ChangeItem(
    id,
    siteMap,
    langTable,
    itemNameTableForm,
    newItemNameForm,
    siteNameTable,
    siteItemTable,
    newSiteItemForm,
    updateCategoryForm,
    categoryTable,
    itemDescriptionTableForm,
    newItemDescriptionForm,
    itemPriceTableForm,
    newItemPriceForm,
    taxTable,
    currencyTable,
    itemInSiteTable,
    itemMetadataTableForm,
    newItemMetadataForm,
    siteItemMetadataTableForm,
    newSiteItemMetadataForm,
    siteItemTextMetadataTableForm,
    newSiteItemTextMetadataForm,
    itemTextMetadataTableForm,
    newItemTextMetadataForm,
    attachmentNames,
    couponForm,
    newSupplementalCategoryForm,
    supplementalCategories
  )

  val ItemDescriptionSize: () => Int = Cache.config(
    _.getInt("itemDescription.size").getOrElse(2048)
  )

  val StoreOwnerCanModifyAllItemProperties: () => Boolean = Cache.config(
    _.getBoolean("storeOwnerCanModifyAllItemProperties").getOrElse(false)
  )

  def isPermitted(login: LoginSession): Boolean =
    login.isSuperUser || login.isSiteOwner && StoreOwnerCanModifyAllItemProperties()

  val createItemForm = Form(
    mapping(
      "langId" -> longNumber,
      "siteId" -> longNumber,
      "categoryId" -> longNumber,
      "itemName" -> text.verifying(nonEmpty, maxLength(255)),
      "taxId" -> longNumber,
      "currencyId" -> longNumber,
      "price" -> bigDecimal.verifying(min(BigDecimal(0))),
      "listPrice" -> optional(bigDecimal.verifying(min(BigDecimal(0)))),
      "costPrice" -> bigDecimal.verifying(min(BigDecimal(0))),
      "description" -> text.verifying(maxLength(ItemDescriptionSize())),
      "isCoupon" ->boolean
    ) (CreateItem.apply)(CreateItem.unapply)
  )

  def index = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      Ok(views.html.admin.itemMaintenance())
    }
  }

  def startCreateNewItem = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      DB.withConnection { implicit conn =>
        Ok(
          views.html.admin.createNewItem(
            createItemForm, LocaleInfo.localeTable, Category.tableForDropDown,
            if (login.isSuperUser) Site.tableForDropDown
            else {
              val site = Site(login.siteUser.get.siteId)
              List((site.id.get.toString, site.name))
            },
            Tax.tableForDropDown, CurrencyInfo.tableForDropDown
          )
        )
      }
    }
  }

  def createNewItem = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      createItemForm.bindFromRequest.fold(
        formWithErrors => {
          logger.error("Validation error in ItemMaintenance.createNewItem." + formWithErrors + ".")
          BadRequest(
            DB.withConnection { implicit conn =>
              views.html.admin.createNewItem(
                formWithErrors, LocaleInfo.localeTable, Category.tableForDropDown,
                Site.tableForDropDown, Tax.tableForDropDown, CurrencyInfo.tableForDropDown
              )
            }
          )
        },
        newItem => {
          DB.withConnection { implicit conn =>
            newItem.save()
          }
          Redirect(
            routes.ItemMaintenance.startCreateNewItem
          ).flashing("message" -> Messages("itemIsCreated"))
        }
      )
    }
  }

  def editItem(
    qs: List[String], pgStart: Int, pgSize: Int, orderBySpec: String
  ) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      val queryStr = if (qs.size == 1) QueryString(qs.head) else QueryString(qs.filter {! _.isEmpty})
      DB.withConnection { implicit conn =>
        login.role match {
          case Buyer => throw new Error("Logic error.")

          case SuperUser =>
            val list = Item.listForMaintenance(
              siteUser = None, locale = LocaleInfo.getDefault, queryString = queryStr, page = pgStart,
              pageSize = pgSize, orderBy = OrderBy(orderBySpec)
            )

            Ok(views.html.admin.editItem(queryStr, list))

          case SiteOwner(siteOwner) =>
            val list = Item.listForMaintenance(
              siteUser = Some(siteOwner), locale = LocaleInfo.getDefault, queryString = queryStr, page = pgStart,
              pageSize = pgSize, orderBy = OrderBy(orderBySpec)
            )
            Ok(views.html.admin.editItem(queryStr, list))
        }
      }
    }
  }

  def siteListAsMap: Map[Long, Site] = {
    DB.withConnection { implicit conn => {
      Site.listAsMap
    }}
  }

  def taxTable(implicit lang: Lang): Seq[(String, String)] = DB.withConnection { implicit conn =>
    Tax.tableForDropDown
  }

  def currencyTable: Seq[(String, String)] = DB.withConnection { implicit conn =>
    CurrencyInfo.tableForDropDown
  }

  def itemMetadataTable(implicit lang: Lang): Seq[(String, String)] = ItemNumericMetadataType.all.map {
    e => (e.ordinal.toString, Messages("itemNumericMetadata" + e.toString))
  }

  def itemTextMetadataTable(implicit lang: Lang): Seq[(String, String)] = ItemTextMetadataType.all.map {
    e => (e.ordinal.toString, Messages("itemTextMetadata" + e.toString))
  }

  def siteItemMetadataTable(implicit lang: Lang): Seq[(String, String)] = SiteItemNumericMetadataType.all.map {
    e => (e.ordinal.toString, Messages("siteItemMetadata" + e.toString))
  }

  def siteItemTextMetadataTable(implicit lang: Lang): Seq[(String, String)] = SiteItemTextMetadataType.all.map {
    e => (e.ordinal.toString, Messages("siteItemTextMetadata" + e.toString))
  }

  def startChangeItem(id: Long) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      Ok(views.html.admin.changeItem(
        createChangeItem(id, login, request2lang)()
      ))
    }
  }

  val changeItemNameForm = Form(
    mapping(
      "itemNames" -> seq(
        mapping(
          "localeId" -> longNumber,
          "itemName" -> text.verifying(nonEmpty, maxLength(255))
        ) (ChangeItemName.apply)(ChangeItemName.unapply)
      )
    ) (ChangeItemNameTable.apply)(ChangeItemNameTable.unapply)
  )

  val changeItemMetadataForm = Form(
    mapping(
      "itemMetadatas" -> seq(
        mapping(
          "metadataType" -> number,
          "metadata" -> longNumber
        ) (ChangeItemMetadata.apply)(ChangeItemMetadata.unapply)
      )
    ) (ChangeItemMetadataTable.apply)(ChangeItemMetadataTable.unapply)
  )

  val changeItemTextMetadataForm = Form(
    mapping(
      "itemMetadatas" -> seq(
        mapping(
          "metadataType" -> number,
          "metadata" -> text
        ) (ChangeItemTextMetadata.apply)(ChangeItemTextMetadata.unapply)
      )
    ) (ChangeItemTextMetadataTable.apply)(ChangeItemTextMetadataTable.unapply)
  )

  val changeSiteItemMetadataForm = Form(
    mapping(
      "siteItemMetadatas" -> seq(
        mapping(
          "siteId" -> longNumber,
          "metadataType" -> number,
          "metadata" -> longNumber
        ) (ChangeSiteItemMetadata.apply)(ChangeSiteItemMetadata.unapply)
      )
    ) (ChangeSiteItemMetadataTable.apply)(ChangeSiteItemMetadataTable.unapply)
  )

  val changeSiteItemTextMetadataForm = Form(
    mapping(
      "siteItemTextMetadatas" -> seq(
        mapping(
          "siteId" -> longNumber,
          "metadataType" -> number,
          "metadata" -> text
        ) (ChangeSiteItemTextMetadata.apply)(ChangeSiteItemTextMetadata.unapply)
      )
    ) (ChangeSiteItemTextMetadataTable.apply)(ChangeSiteItemTextMetadataTable.unapply)
  )

  val addItemNameForm = Form(
    mapping(
      "localeId" -> longNumber,
      "itemName" -> text.verifying(nonEmpty, maxLength(255))
    ) (ChangeItemName.apply)(ChangeItemName.unapply)
  )

  val addItemMetadataForm = Form(
    mapping(
      "metadataType" -> number,
      "metadata" -> longNumber
    ) (ChangeItemMetadata.apply)(ChangeItemMetadata.unapply)
  )

  val addItemTextMetadataForm = Form(
    mapping(
      "metadataType" -> number,
      "metadata" -> text
    ) (ChangeItemTextMetadata.apply)(ChangeItemTextMetadata.unapply)
  )

  val addSiteItemMetadataForm = Form(
    mapping(
      "siteId" -> longNumber,
      "metadataType" -> number,
      "metadata" -> longNumber
    ) (ChangeSiteItemMetadata.apply)(ChangeSiteItemMetadata.unapply)
  )

  val addSiteItemTextMetadataForm = Form(
    mapping(
      "siteId" -> longNumber,
      "metadataType" -> number,
      "metadata" -> text
    ) (ChangeSiteItemTextMetadata.apply)(ChangeSiteItemTextMetadata.unapply)
  )

  val couponForm = Form(
    mapping(
      "isCoupon" ->boolean
    ) (ChangeCoupon.apply)(ChangeCoupon.unapply)
  )

  val addSupplementalCategoryForm = Form(
    mapping(
      "categoryId" -> longNumber
    ) (ChangeSupplementalCategory.apply)(ChangeSupplementalCategory.unapply)
  )

  def createItemNameTable(id: Long): Form[ChangeItemNameTable] = {
    DB.withConnection { implicit conn => {
      val itemNames = ItemName.list(ItemId(id)).values.map {
        n => ChangeItemName(n.localeId, n.name)
      }.toSeq

      changeItemNameForm.fill(ChangeItemNameTable(itemNames))
    }}
  }

  def createItemMetadataTable(id: Long): Form[ChangeItemMetadataTable] = {
    DB.withConnection { implicit conn => {
      val itemMetadatas = ItemNumericMetadata.allById(ItemId(id)).values.map {
        n => ChangeItemMetadata(n.metadataType.ordinal, n.metadata)
      }.toSeq

      changeItemMetadataForm.fill(ChangeItemMetadataTable(itemMetadatas))
    }}
  }

  def createItemTextMetadataTable(id: Long): Form[ChangeItemTextMetadataTable] = {
    DB.withConnection { implicit conn => {
      val itemMetadatas = ItemTextMetadata.allById(ItemId(id)).values.map {
        n => ChangeItemTextMetadata(n.metadataType.ordinal, n.metadata)
      }.toSeq

      changeItemTextMetadataForm.fill(ChangeItemTextMetadataTable(itemMetadatas))
    }}
  }

  def createSiteItemMetadataTable(id: Long): Form[ChangeSiteItemMetadataTable] = {
    DB.withConnection { implicit conn => {
      val itemMetadata = SiteItemNumericMetadata.allById(ItemId(id)).values.map {
        n => ChangeSiteItemMetadata(n.siteId, n.metadataType.ordinal, n.metadata)
      }.toSeq

      changeSiteItemMetadataForm.fill(ChangeSiteItemMetadataTable(itemMetadata))
    }}
  }

  def createSiteItemTextMetadataTable(id: Long): Form[ChangeSiteItemTextMetadataTable] = {
    DB.withConnection { implicit conn => {
      val itemMetadata = SiteItemTextMetadata.allById(ItemId(id)).values.map {
        n => ChangeSiteItemTextMetadata(n.siteId, n.metadataType.ordinal, n.metadata)
      }.toSeq

      changeSiteItemTextMetadataForm.fill(ChangeSiteItemTextMetadataTable(itemMetadata))
    }}
  }

  def changeItemName(id: Long) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeUser(isPermitted(login)) {
      changeItemNameForm.bindFromRequest.fold(
        formWithErrors => {
          logger.error("Validation error in ItemMaintenance.changeItemName." + formWithErrors + ".")
          BadRequest(
            views.html.admin.changeItem(
              createChangeItem(
                id, login, request2lang
              )(
                itemNameTableForm = formWithErrors
              )
            )
          )
        },
        newItem => {
          DB.withConnection { implicit conn =>
            newItem.update(id)
          }
          Redirect(
            routes.ItemMaintenance.startChangeItem(id)
          ).flashing("message" -> Messages("itemIsUpdated"))
        }
      )
    }
  }

  def addItemName(id: Long) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeUser(isPermitted(login)) {
      addItemNameForm.bindFromRequest.fold(
        formWithErrors => {
          logger.error("Validation error in ItemMaintenance.addItemName." + formWithErrors + ".")
          BadRequest(
            views.html.admin.changeItem(
              createChangeItem(
                id, login, request2lang
              )(
                newItemNameForm = formWithErrors
              )
            )
          )
        },
        newItem => {
          try {
            newItem.add(id)

            Redirect(
              routes.ItemMaintenance.startChangeItem(id)
            ).flashing("message" -> Messages("itemIsUpdated"))
          }
          catch {
            case e: UniqueConstraintException => {
              BadRequest(
                views.html.admin.changeItem(
                  createChangeItem(
                    id, login, request2lang
                  )(
                    newItemNameForm = addItemNameForm.fill(newItem).withError("localeId", "unique.constraint.violation")
                  )
                )
              )
            }
          }
        }
      )
    }
  }

  def removeItemName(itemId: Long, localeId: Long) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeUser(isPermitted(login)) {
      DB.withConnection { implicit conn =>
        ItemName.remove(ItemId(itemId), localeId)
      }

      Redirect(
        routes.ItemMaintenance.startChangeItem(itemId)
      )
    }
  }

  def removeItemMetadata(itemId: Long, metadataType: Int) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeUser(isPermitted(login)) {
      DB.withConnection { implicit conn =>
        ItemNumericMetadata.remove(ItemId(itemId), metadataType)
      }

      Redirect(
        routes.ItemMaintenance.startChangeItem(itemId)
      )
    }
  }

  def removeItemTextMetadata(itemId: Long, metadataType: Int) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      DB.withConnection { implicit conn =>
        ItemTextMetadata.remove(ItemId(itemId), metadataType)
      }

      Redirect(
        routes.ItemMaintenance.startChangeItem(itemId)
      )
    }
  }

  def removeSiteItemMetadata(
    itemId: Long, siteId: Long, metadataType: Int
  ) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      DB.withConnection { implicit conn =>
        SiteItemNumericMetadata.remove(ItemId(itemId), siteId, metadataType)
      }

      Redirect(
        routes.ItemMaintenance.startChangeItem(itemId)
      )
    }
  }

  def removeSiteItemTextMetadata(
    itemId: Long, siteId: Long, metadataType: Int
  ) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      DB.withConnection { implicit conn =>
        SiteItemTextMetadata.remove(ItemId(itemId), siteId, metadataType)
      }

      Redirect(
        routes.ItemMaintenance.startChangeItem(itemId)
      )
    }
  }

  val addSiteItemForm = Form(
    mapping(
      "siteId" -> longNumber
    ) (ChangeSiteItem.apply)(ChangeSiteItem.unapply)
  )

  def createSiteTable(implicit login: LoginSession): Seq[(String, String)] = {
    DB.withConnection { implicit conn => {
      Site.tableForDropDown
    }}
  }

  def createSiteTable(id: Long)(implicit login: LoginSession): Seq[(String, String)] = {
    DB.withConnection { implicit conn => {
      Site.tableForDropDown(id)
    }}
  }    

  def createSiteItemTable(itemId: Long): Seq[(Site, SiteItem)] = {
    DB.withConnection { implicit conn => {
      SiteItem.list(ItemId(itemId))
    }}
  }

  def addSiteItem(id: Long) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      addSiteItemForm.bindFromRequest.fold(
        formWithErrors => {
          logger.error("Validation error in ItemMaintenance.addSiteItem." + formWithErrors + ".")
          BadRequest(
            views.html.admin.changeItem(
              createChangeItem(
                id, login, request2lang
              )(
                newSiteItemForm = formWithErrors
              )
            )
          )
        },
        newSiteItem => {
          try {
            DB.withConnection { implicit conn =>
              newSiteItem.add(id)
            }

            Redirect(
              routes.ItemMaintenance.startChangeItem(id)
            ).flashing("message" -> Messages("itemIsUpdated"))
          }
          catch {
            case e: UniqueConstraintException => {
              BadRequest(
                views.html.admin.changeItem(
                  createChangeItem(
                    id, login, request2lang
                  )(
                    newSiteItemForm = addSiteItemForm.fill(newSiteItem).withError("siteId", "unique.constraint.violation")
                  )
                )
              )
            }
          }
        }
      )
    }
  }

  def removeSiteItem(itemId: Long, siteId: Long) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      DB.withConnection { implicit conn =>
        SiteItem.remove(ItemId(itemId), siteId)
        ItemPrice.remove(ItemId(itemId), siteId)
      }

      Redirect(
        routes.ItemMaintenance.startChangeItem(itemId)
      )
    }
  }

  val updateCategoryForm = Form(
    mapping(
      "categoryId" -> longNumber
    ) (ChangeItemCategory.apply)(ChangeItemCategory.unapply)
  )

  def createItemCategoryForm(id: Long): Form[ChangeItemCategory] = {
    DB.withConnection { implicit conn => {
      val item = Item(id)
      updateCategoryForm.fill(ChangeItemCategory(item.categoryId))
    }}
  }

  def createCouponForm(id: ItemId): Form[ChangeCoupon] = {
    DB.withConnection { implicit conn => {
      couponForm.fill(ChangeCoupon(Coupon.isCoupon(id)))
    }}
  }

  def createCategoryTable(implicit lang: Lang): Seq[(String, String)] = {
    DB.withConnection { implicit conn => {
      Category.tableForDropDown
    }}
  }

  def updateItemAsCoupon(id: Long) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      couponForm.bindFromRequest.fold(
        formWithErrors => {
          logger.error("Validation error in ItemMaintenance.updateItemAsItem." + formWithErrors + ".")
          BadRequest(
            views.html.admin.changeItem(
              createChangeItem(
                id, login, request2lang
              )(
                couponForm = formWithErrors
              )
            )
          )
        },
        newIsCoupon => {
          DB.withConnection { implicit conn =>
            newIsCoupon.update(ItemId(id))
          }
          Redirect(
            routes.ItemMaintenance.startChangeItem(id)
          ).flashing("message" -> Messages("itemIsUpdated"))
        }
      )
    }
  }

  def updateItemCategory(id: Long) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      updateCategoryForm.bindFromRequest.fold(
        formWithErrors => {
          logger.error("Validation error in ItemMaintenance.updateItemCategory." + formWithErrors + ".")
          BadRequest(
            views.html.admin.changeItem(
              createChangeItem(
                id, login, request2lang
              )(
                updateCategoryForm = formWithErrors
              )
            )
          )
        },
        newItemCategory => {
          DB.withConnection { implicit conn =>
            newItemCategory.update(id)
          }
          Redirect(
            routes.ItemMaintenance.startChangeItem(id)
          ).flashing("message" -> Messages("itemIsUpdated"))
        }
      )
    }
  }

  val changeItemDescriptionForm = Form(
    mapping(
      "itemDescriptions" -> seq(
        mapping(
          "siteId" -> longNumber,
          "localeId" -> longNumber,
          "itemDescription" -> text.verifying(nonEmpty, maxLength(ItemDescriptionSize()))
        ) (ChangeItemDescription.apply)(ChangeItemDescription.unapply)
      )
    ) (ChangeItemDescriptionTable.apply)(ChangeItemDescriptionTable.unapply)
  )

  val addItemDescriptionForm = Form(
    mapping(
      "siteId" -> longNumber,
      "localeId" -> longNumber,
      "itemDescription" -> text.verifying(nonEmpty, maxLength(ItemDescriptionSize()))
    ) (ChangeItemDescription.apply)(ChangeItemDescription.unapply)
  )

  def createItemDescriptionTable(id: Long): Form[ChangeItemDescriptionTable] = {
    DB.withConnection { implicit conn => {
      val itemDescriptions = ItemDescription.list(ItemId(id)).map {
        n => ChangeItemDescription(n._1, n._2.id, n._3.description)
      }.toSeq

      changeItemDescriptionForm.fill(ChangeItemDescriptionTable(itemDescriptions))
    }}
  }

  def changeItemDescription(id: Long) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      changeItemDescriptionForm.bindFromRequest.fold(
        formWithErrors => {
          logger.error("Validation error in ItemMaintenance.changeItem." + formWithErrors + ".")
          BadRequest(
            views.html.admin.changeItem(
              createChangeItem(
                id, login, request2lang
              )(
                itemDescriptionTableForm = formWithErrors
              )
            )
          )
        },
        newItem => {
          DB.withTransaction { implicit conn =>
            newItem.update(id)
          }
          Redirect(
            routes.ItemMaintenance.startChangeItem(id)
          ).flashing("message" -> Messages("itemIsUpdated"))
        }
      )
    }
  }

  def addItemDescription(id: Long) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      addItemDescriptionForm.bindFromRequest.fold(
        formWithErrors => {
          logger.error("Validation error in ItemMaintenance.changeItem." + formWithErrors + ".")
          BadRequest(
            views.html.admin.changeItem(
              createChangeItem(
                id, login, request2lang
              )(
                newItemDescriptionForm = formWithErrors
              )
            )
          )
        },
        newItem => {
          try {
            newItem.add(id)

            Redirect(
              routes.ItemMaintenance.startChangeItem(id)
            ).flashing("message" -> Messages("itemIsUpdated"))
          }
          catch {
            case e: UniqueConstraintException => {
              BadRequest(
                views.html.admin.changeItem(
                  createChangeItem(
                    id, login, request2lang
                  )(
                    newItemDescriptionForm = addItemDescriptionForm
                      .fill(newItem)
                      .withError("localeId", "unique.constraint.violation")
                      .withError("siteId", "unique.constraint.violation")
                  )
                )
              )
            }
          }
        }
      )
    }
  }

  def removeItemDescription(
    siteId: Long, itemId: Long, localeId: Long
  ) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      DB.withConnection { implicit conn =>
        ItemDescription.remove(siteId, ItemId(itemId), localeId)
      }

      Redirect(
        routes.ItemMaintenance.startChangeItem(itemId)
      )
    }
  }

  val changeItemPriceForm = Form(
    mapping(
      "itemPrices" -> seq(
        mapping(
          "siteId" -> longNumber,
          "itemPriceId" -> longNumber,
          "itemPriceHistoryId" -> longNumber,
          "taxId" -> longNumber,
          "currencyId" -> longNumber,
          "itemPrice" -> bigDecimal.verifying(min(BigDecimal(0))),
          "listPrice" -> optional(bigDecimal.verifying(min(BigDecimal(0)))),
          "costPrice" -> bigDecimal.verifying(min(BigDecimal(0))),
          "validUntil" -> jodaDate("yyyy-MM-dd HH:mm:ss")
        ) (ChangeItemPrice.apply)(ChangeItemPrice.unapply)
      )
    ) (ChangeItemPriceTable.apply)(ChangeItemPriceTable.unapply)
  )

  val addItemPriceForm = Form(
    mapping(
      "siteId" -> longNumber,
      "itemPriceId" -> ignored(0L),
      "itemPriceHistoryId" -> ignored(0L),
      "taxId" -> longNumber,
      "currencyId" -> longNumber,
      "itemPrice" -> bigDecimal.verifying(min(BigDecimal(0))),
      "listPrice" -> optional(bigDecimal.verifying(min(BigDecimal(0)))),
      "costPrice" -> bigDecimal.verifying(min(BigDecimal(0))),
      "validUntil" -> jodaDate("yyyy-MM-dd HH:mm:ss")
    ) (ChangeItemPrice.apply)(ChangeItemPrice.unapply)
  )

  def createItemPriceTable(itemId: Long): Form[ChangeItemPriceTable] = {
    DB.withConnection { implicit conn => {
      val histories = ItemPriceHistory.listByItemId(ItemId(itemId)).map {
        e => ChangeItemPrice(
          e._1.siteId, e._2.itemPriceId, e._2.id.get, e._2.taxId, 
          e._2.currency.id, e._2.unitPrice, e._2.listPrice, e._2.costPrice, new DateTime(e._2.validUntil)
        )
      }.toSeq

      changeItemPriceForm.fill(ChangeItemPriceTable(histories))
    }}
  }

  def changeItemPrice(id: Long) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      changeItemPriceForm.bindFromRequest.fold(
        formWithErrors => {
          logger.error("Validation error in ItemMaintenance.changeItemPrice." + formWithErrors + ".")
          BadRequest(
            views.html.admin.changeItem(
              createChangeItem(
                id, login, request2lang
              )(
                itemPriceTableForm = formWithErrors
              )
            )
          )
        },
        newPrice => {
          DB.withConnection { implicit conn =>
            newPrice.update()
          }
          Redirect(
            routes.ItemMaintenance.startChangeItem(id)
          ).flashing("message" -> Messages("itemIsUpdated"))
        }
      )
    }
  }

  def addItemPrice(id: Long) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      addItemPriceForm.bindFromRequest.fold(
        formWithErrors => {
          logger.error("Validation error in ItemMaintenance.addItemPrice " + formWithErrors + ".")
          BadRequest(
            views.html.admin.changeItem(
              createChangeItem(
                id, login, request2lang
              )(
                newItemPriceForm = formWithErrors
              )
            )
          )
        },
        newHistory => {
          try {
            DB.withConnection { implicit conn =>
              newHistory.add(id)
            }
            Redirect(
              routes.ItemMaintenance.startChangeItem(id)
            ).flashing("message" -> Messages("itemIsUpdated"))
          }
          catch {
            case e: UniqueConstraintException => {
              BadRequest(
                views.html.admin.changeItem(
                  createChangeItem(
                    id, login, request2lang
                  )(
                    newItemPriceForm = addItemPriceForm
                      .fill(newHistory)
                      .withError("siteId", "unique.constraint.violation")
                      .withError("validUntil", "unique.constraint.violation")
                  )
                )
              )
            }
          }
        }
      )
    }
  }

  def removeItemPrice(
    itemId: Long, siteId: Long, itemPriceHistoryId: Long
  ) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      DB.withConnection { implicit conn =>
        ItemPriceHistory.remove(ItemId(itemId), siteId, itemPriceHistoryId)
      }

      Redirect(
        routes.ItemMaintenance.startChangeItem(itemId)
      )
    }
  }

  def changeItemMetadata(itemId: Long) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      changeItemMetadataForm.bindFromRequest.fold(
        formWithErrors => {
          logger.error("Validation error in ItemMaintenance.changeItemMetadata." + formWithErrors + ".")
          BadRequest(
            views.html.admin.changeItem(
              createChangeItem(
                itemId, login, request2lang
              )(
                itemMetadataTableForm = formWithErrors
              )
            )
          )
        },
        newMetadata => {
          DB.withTransaction { implicit conn =>
            newMetadata.update(itemId)
          }
          Redirect(
            routes.ItemMaintenance.startChangeItem(itemId)
          ).flashing("message" -> Messages("itemIsUpdated"))
        }
      )
    }
  }

  def changeItemTextMetadata(itemId: Long) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      changeItemTextMetadataForm.bindFromRequest.fold(
        formWithErrors => {
          logger.error("Validation error in ItemMaintenance.changeItemTextMetadata." + formWithErrors + ".")
          BadRequest(
            views.html.admin.changeItem(
              createChangeItem(
                itemId, login, request2lang
              )(
                itemTextMetadataTableForm = formWithErrors
              )
            )
          )
        },
        newMetadata => {
          DB.withTransaction { implicit conn =>
            newMetadata.update(itemId)
          }
          Redirect(
            routes.ItemMaintenance.startChangeItem(itemId)
          ).flashing("message" -> Messages("itemIsUpdated"))
        }
      )
    }
  }

  def changeSiteItemMetadata(itemId: Long) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      changeSiteItemMetadataForm.bindFromRequest.fold(
        formWithErrors => {
          logger.error("Validation error in ItemMaintenance.changeSiteItemMetadata." + formWithErrors + ".")
          BadRequest(
            views.html.admin.changeItem(
              createChangeItem(
                itemId, login, request2lang
              )(
                siteItemMetadataTableForm = formWithErrors
              )
            )
          )
        },
        newMetadata => {
          DB.withConnection { implicit conn =>
            newMetadata.update(itemId)
          }
          Redirect(
            routes.ItemMaintenance.startChangeItem(itemId)
          ).flashing("message" -> Messages("itemIsUpdated"))
        }
      )
    }
  }

  def changeSiteItemTextMetadata(itemId: Long) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      changeSiteItemTextMetadataForm.bindFromRequest.fold(
        formWithErrors => {
          logger.error("Validation error in ItemMaintenance.changeSiteItemTextMetadata." + formWithErrors + ".")
          BadRequest(
            views.html.admin.changeItem(
              createChangeItem(
                itemId, login, request2lang
              )(
                siteItemTextMetadataTableForm = formWithErrors
              )
            )
          )
        },
        newMetadata => {
          DB.withConnection { implicit conn =>
            newMetadata.update(itemId)
          }
          Redirect(
            routes.ItemMaintenance.startChangeItem(itemId)
          ).flashing("message" -> Messages("itemIsUpdated"))
        }
      )
    }
  }

  def addItemMetadata(id: Long) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      addItemMetadataForm.bindFromRequest.fold(
        formWithErrors => {
          logger.error("Validation error in ItemMaintenance.addItemMetadata." + formWithErrors + ".")
          BadRequest(
            views.html.admin.changeItem(
              createChangeItem(
                id, login, request2lang
              )(
                newItemMetadataForm = formWithErrors
              )
            )
          )
        },
        newMetadata => {
          try {
            newMetadata.add(id)

            Redirect(
              routes.ItemMaintenance.startChangeItem(id)
            ).flashing("message" -> Messages("itemIsUpdated"))
          }
          catch {
            case e: UniqueConstraintException => {
              BadRequest(
                views.html.admin.changeItem(
                  createChangeItem(
                    id, login, request2lang
                  )(
                    newItemMetadataForm = addItemMetadataForm
                      .fill(newMetadata)
                      .withError("metadataType", "unique.constraint.violation")
                  )
                )
              )
            }
          }
        }
      )
    }
  }

  def addItemTextMetadata(id: Long) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      addItemTextMetadataForm.bindFromRequest.fold(
        formWithErrors => {
          logger.error("Validation error in ItemMaintenance.addItemTextMetadata." + formWithErrors + ".")
          BadRequest(
            views.html.admin.changeItem(
              createChangeItem(
                id, login, request2lang
              )(
                newItemTextMetadataForm = formWithErrors
              )
            )
          )
        },
        newMetadata => {
          try {
            newMetadata.add(id)

            Redirect(
              routes.ItemMaintenance.startChangeItem(id)
            ).flashing("message" -> Messages("itemIsUpdated"))
          }
          catch {
            case e: UniqueConstraintException => {
              BadRequest(
                views.html.admin.changeItem(
                  createChangeItem(
                    id, login, request2lang
                  )(
                    newItemTextMetadataForm = addItemTextMetadataForm
                      .fill(newMetadata)
                      .withError("metadataType", "unique.constraint.violation")
                  )
                )
              )
            }
          }
        }
      )
    }
  }

  def addSiteItemMetadata(id: Long) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeAdmin(login) {
      addSiteItemMetadataForm.bindFromRequest.fold(
        formWithErrors => {
          logger.error("Validation error in ItemMaintenance.addSiteItemMetadata." + formWithErrors + ".")
          BadRequest(
            views.html.admin.changeItem(
              createChangeItem(
                id, login, request2lang
              )(
                newSiteItemMetadataForm = formWithErrors
              )
            )
          )
        },
        newMetadata => {
          try {
            DB.withConnection { implicit conn =>
              newMetadata.add(id)
            }

            Redirect(
              routes.ItemMaintenance.startChangeItem(id)
            ).flashing("message" -> Messages("itemIsUpdated"))
          }
          catch {
            case e: UniqueConstraintException => {
              BadRequest(
                views.html.admin.changeItem(
                  createChangeItem(
                    id, login, request2lang
                  )(
                    newSiteItemMetadataForm = addSiteItemMetadataForm
                      .fill(newMetadata)
                      .withError("metadataType", "unique.constraint.violation")
                  )
                )
              )
            }
          }
        }
      )
    }
  }

  def addSiteItemTextMetadata(id: Long) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeUser(isPermitted(login)) {
      addSiteItemTextMetadataForm.bindFromRequest.fold(
        formWithErrors => {
          logger.error("Validation error in ItemMaintenance.addSiteItemTextMetadata." + formWithErrors + ".")
          BadRequest(
            views.html.admin.changeItem(
              createChangeItem(
                id, login, request2lang
              )(
                newSiteItemTextMetadataForm = formWithErrors
              )
            )
          )
        },
        newMetadata => {
          try {
            DB.withConnection { implicit conn =>
              newMetadata.add(id)
            }

            Redirect(
              routes.ItemMaintenance.startChangeItem(id)
            ).flashing("message" -> Messages("itemIsUpdated"))
          }
          catch {
            case e: UniqueConstraintException => {
              BadRequest(
                views.html.admin.changeItem(
                  createChangeItem(
                    id, login, request2lang
                  )(
                    newSiteItemTextMetadataForm = addSiteItemTextMetadataForm
                      .fill(newMetadata)
                      .withError("metadataType", "unique.constraint.violation")
                  )
                )
              )
            }
          }
        }
      )
    }
  }

  def addSupplementalCategory(itemId: Long) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeUser(isPermitted(login)) {
      addSupplementalCategoryForm.bindFromRequest.fold(
        formWithErrors => {
          logger.error("Validation error in ItemMaintenance.addSupplementalCategory " + formWithErrors + ".")
          Redirect(
            routes.ItemMaintenance.startChangeItem(itemId)
          ).flashing("errorMessage" -> Messages("unknownError"))
        },
        changeCategory => {
          DB.withConnection { implicit conn =>
            try {
              changeCategory.add(itemId)
            }
            catch {
              case e: UniqueConstraintException => {
                Redirect(
                  routes.ItemMaintenance.startChangeItem(itemId)
                ).flashing("errorMessage" -> Messages("unique.constraint.violation"))
              }
              case e: MaxRowCountExceededException => {
                Redirect(
                  routes.ItemMaintenance.startChangeItem(itemId)
                ).flashing("errorMessage" -> Messages("maxSupplementalCategoryCountSucceeded"))
              }
            }

            Redirect(
              routes.ItemMaintenance.startChangeItem(itemId)
            ).flashing("message" -> Messages("itemIsUpdated"))
          }
        }
      )
    }
  }

  def supplementalCategories(itemId: ItemId, lang: Lang): Seq[(Long, String)] = {
    DB.withConnection { implicit conn =>
      SupplementalCategory.byItemWithName(itemId, lang).map { t =>
        (t._1.categoryId, t._2.name)
      }
    }
  }

  def removeSupplementalCategory(itemId: Long, categoryId: Long) = NeedAuthenticated { implicit request =>
    implicit val login = request.user
    assumeUser(isPermitted(login)) {
      DB.withConnection { implicit conn =>
        SupplementalCategory.remove(ItemId(itemId), categoryId)
      }
      Redirect(
        routes.ItemMaintenance.startChangeItem(itemId)
      ).flashing("message" -> Messages("itemIsUpdated"))
    }
  }
}
