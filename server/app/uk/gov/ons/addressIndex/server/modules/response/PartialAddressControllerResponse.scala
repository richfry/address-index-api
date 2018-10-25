package uk.gov.ons.addressIndex.server.modules.response

import uk.gov.ons.addressIndex.model.server.response.address._
import uk.gov.ons.addressIndex.model.server.response.partialaddress.{AddressByPartialAddressResponse, AddressByPartialAddressResponseContainer}

trait PartialAddressControllerResponse extends AddressResponse {

  def BadRequestPartialTemplate(errors: AddressResponseError*): AddressByPartialAddressResponseContainer = {
    AddressByPartialAddressResponseContainer(
      apiVersion = apiVersion,
      dataVersion = dataVersion,
      response = ErrorPartialAddress,
      status = BadRequestAddressResponseStatus,
      errors = errors
    )
  }

  def ShortSearch: AddressByPartialAddressResponseContainer = {
    BadRequestPartialTemplate(ShortQueryAddressResponseError)
  }

  def FailedRequestToEsPartialAddress: AddressByPartialAddressResponseContainer = {
    AddressByPartialAddressResponseContainer(
      apiVersion = apiVersion,
      dataVersion = dataVersion,
      response = ErrorPartialAddress,
      status = InternalServerErrorAddressResponseStatus,
      errors = Seq(FailedRequestToEsPartialAddressError)
    )
  }

  def ErrorPartialAddress: AddressByPartialAddressResponse = {
    AddressByPartialAddressResponse(
      input = "",
      addresses = Seq.empty,
      filter = "",
      historical = true,
      limit = 10,
      offset = 0,
      total = 0,
      maxScore = 0f,
      startDate = "",
      endDate = "",
      verbose = true
    )
  }

}
