package uk.gov.ons.addressIndex.server.modules.validation

import play.api.mvc.Result
import uk.gov.ons.addressIndex.model.server.response.address._
import uk.gov.ons.addressIndex.server.modules.response.AddressResponse
import uk.gov.ons.addressIndex.server.modules.{ConfigModule, VersionModule}

import scala.concurrent.Future
import scala.util.Try

abstract class AddressValidation (implicit conf: ConfigModule, versionProvider: VersionModule)
  extends Validation with AddressResponse  {

  // get maxima length from config
  val maximumLimit = conf.config.elasticSearch.maximumLimit
  val maximumOffset = conf.config.elasticSearch.maximumOffset

  // override error messages with maxima
  object LimitTooLargeAddressResponseErrorCustom extends AddressResponseError(
    code = 8,
    message = LimitTooLargeAddressResponseError.message.replace("*",maximumLimit.toString)
  )

  object OffsetTooLargeAddressResponseErrorCustom extends AddressResponseError(
    code = 9,
    message = OffsetTooLargeAddressResponseError.message.replace("*",maximumOffset.toString)
  )

  override def LimitTooLarge: AddressBySearchResponseContainer = {
    BadRequestTemplate(LimitTooLargeAddressResponseErrorCustom)
  }

  override def OffsetTooLarge: AddressBySearchResponseContainer = {
    BadRequestTemplate(OffsetTooLargeAddressResponseErrorCustom)
  }

  def validateLimit(limit: Option[String]): Option[Future[Result]] = {

    val defLimit: Int = conf.config.elasticSearch.defaultLimit
    val limval = limit.getOrElse(defLimit.toString)
    val limitInvalid = Try(limval.toInt).isFailure
    val limitInt = Try(limval.toInt).toOption.getOrElse(defLimit)
    val maxLimit: Int = conf.config.elasticSearch.maximumLimit

    if (limitInvalid) {
      logger.systemLog(badRequestMessage =LimitNotNumericAddressResponseError.message)
      Some(futureJsonBadRequest(LimitNotNumeric))
    } else if (limitInt < 1) {
      logger.systemLog(badRequestMessage = LimitTooSmallAddressResponseError.message)
      Some(futureJsonBadRequest(LimitTooSmall))
    } else if (limitInt > maxLimit) {
      logger.systemLog(badRequestMessage = LimitTooLargeAddressResponseErrorCustom.message)
      Some(futureJsonBadRequest(LimitTooLarge))
    } else None
  }

  def validateOffset(offset: Option[String]): Option[Future[Result]] = {
    val maxOffset: Int = conf.config.elasticSearch.maximumOffset
    val defOffset: Int = conf.config.elasticSearch.defaultOffset
    val offval = offset.getOrElse(defOffset.toString)
    val offsetInvalid = Try(offval.toInt).isFailure
    val offsetInt = Try(offval.toInt).toOption.getOrElse(defOffset)

    if (offsetInvalid) {
      logger.systemLog(badRequestMessage = OffsetNotNumericAddressResponseError.message)
      Some(futureJsonBadRequest(OffsetNotNumeric))
    } else if (offsetInt < 0) {
      logger.systemLog(badRequestMessage = OffsetTooSmallAddressResponseError.message)
      Some(futureJsonBadRequest(OffsetTooSmall))
    } else if (offsetInt > maxOffset) {
      logger.systemLog(badRequestMessage = OffsetTooLargeAddressResponseErrorCustom.message)
      Some(futureJsonBadRequest(OffsetTooLarge))
    } else None
  }

  def validateInput(input: String): Option[Future[Result]] = {
    if (input.isEmpty) {
      logger.systemLog(badRequestMessage = EmptyQueryAddressResponseError.message)
      Some(futureJsonBadRequest(EmptySearch))
    } else None
  }
}
