package uk.gov.ons.addressIndex.server.controllers

import javax.inject.{Inject, Singleton}
import play.api.mvc._
import uk.gov.ons.addressIndex.model.config.QueryParamsConfig
import uk.gov.ons.addressIndex.model.db.{BulkAddressRequestData, BulkAddresses}
import uk.gov.ons.addressIndex.model.server.response.address.OkAddressResponseStatus
import uk.gov.ons.addressIndex.model.server.response.bulk.{AddressBulkResponseAddress, AddressBulkResponseContainer}
import uk.gov.ons.addressIndex.model.{BulkBody, BulkBodyDebug}
import uk.gov.ons.addressIndex.server.modules.response.AddressControllerResponse
import uk.gov.ons.addressIndex.server.modules.validation.BatchControllerValidation
import uk.gov.ons.addressIndex.server.modules.{ConfigModule, ElasticsearchRepository, ParserModule, VersionModule}
import uk.gov.ons.addressIndex.server.utils.AddressAPILogger

import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

@Singleton
class BatchController @Inject()(val controllerComponents: ControllerComponents,
  esRepo: ElasticsearchRepository,
  parser: ParserModule,
  conf: ConfigModule,
  versionProvider: VersionModule,
  batchValidation: BatchControllerValidation
)(implicit ec: ExecutionContext)
  extends PlayHelperController(versionProvider) with AddressControllerResponse {

  lazy val logger = AddressAPILogger("address-index-server:BatchController")

  /**
    * a POST route which will process all `BulkQuery` items in the `BulkBody`
    *
    * @return reduced information on found addresses (uprn, formatted address)
    */
  def bulk(limitperaddress: Option[String], startDate: Option[String] = None, endDate: Option[String] = None,
           historical: Option[String] = None, matchthreshold: Option[String] = None): Action[BulkBody] = Action(parse.json[BulkBody]) { implicit request =>

    logger.info(s"#bulkQuery with ${request.body.addresses.size} items")

    val clusterid = conf.config.elasticSearch.clusterPolicies.bulk

    val startDateVal = startDate.getOrElse("")
    val endDateVal = endDate.getOrElse("")

    // get the defaults and maxima for the paging parameters from the config
    val defLimit = conf.config.bulk.limitperaddress
    val limval = limitperaddress.getOrElse(defLimit.toString)
    val limitInt = Try(limval.toInt).toOption.getOrElse(defLimit)

    val defThreshold = conf.config.bulk.matchThreshold
    val threshval = matchthreshold.getOrElse(defThreshold.toString)
    val thresholdFloat = Try(threshval.toFloat).toOption.getOrElse(defThreshold)

    val result: Option[Result] =
      batchValidation.validateBatchSource
        .orElse(batchValidation.validateBatchStartDate(startDateVal))
        .orElse(batchValidation.validateBatchEndDate(endDateVal))
        .orElse(batchValidation.validateBatchKeyStatus)
        .orElse(batchValidation.validateBatchKeyStatus)
        .orElse(batchValidation.validateBatchAddressLimit(Some(limval)))
        .orElse(batchValidation.validateBatchThreshold(matchthreshold))
        .orElse(None)

    result match {

      case Some(res) =>
        res

      case _ =>
        val hist = historical match {
          case Some(x) => Try(x.toBoolean).getOrElse(true)
          case None => true
        }
        val requestsData: Stream[BulkAddressRequestData] = requestDataFromRequest(request)
        val configOverwrite: Option[QueryParamsConfig] = request.body.config

        bulkQuery(requestsData, configOverwrite, Some(limitInt), includeFullAddress = false,
          startDate = startDateVal, endDate = endDateVal, historical = hist, thresholdFloat, clusterid = clusterid)
    }
  }

  /**
    * a POST route which will process all `BulkQuery` items in the `BulkBody`
    * this version is slower and more memory-consuming
    *
    * @return all the information on found addresses (uprn, formatted address, found address json object)
    */
  def bulkFull(limitperaddress: Option[String], startDate: Option[String] = None, endDate: Option[String] = None,
               historical: Option[String] = None, matchthreshold: Option[String] = None): Action[BulkBody] = Action(
    parse.json[BulkBody]) { implicit request =>

    logger.info(s"#bulkFullQuery with ${request.body.addresses.size} items")

    val hist = historical match {
      case Some(x) => Try(x.toBoolean).getOrElse(true)
      case None => true
    }

    val startDateVal = startDate.getOrElse("")
    val endDateVal = endDate.getOrElse("")

    val defLimit = conf.config.bulk.limitperaddress
    val limval = limitperaddress.getOrElse(defLimit.toString)
    val limitInt = Try(limval.toInt).toOption.getOrElse(defLimit)

    val defThreshold = conf.config.bulk.matchThreshold
    val threshval = matchthreshold.getOrElse(defThreshold.toString)
    val thresholdFloat = Try(threshval.toFloat).toOption.getOrElse(defThreshold)

    logger.info("threshold = " + thresholdFloat)

    val result: Option[Result] =
      batchValidation.validateBatchSource
        .orElse(batchValidation.validateBatchStartDate(startDateVal))
        .orElse(batchValidation.validateBatchEndDate(endDateVal))
        .orElse(batchValidation.validateBatchKeyStatus)
        .orElse(batchValidation.validateBatchAddressLimit(Some(limval)))
        .orElse(batchValidation.validateBatchThreshold(matchthreshold))
        .orElse(None)

    result match {
      case Some(res) =>
        res

      case _ =>
        val requestsData: Stream[BulkAddressRequestData] = requestDataFromRequest(request)
        val configOverwrite: Option[QueryParamsConfig] = request.body.config

        bulkQuery(requestsData, configOverwrite, Some(limitInt), includeFullAddress = true, startDate = startDateVal, endDate = endDateVal, hist, thresholdFloat)
    }
  }

  /**
    * Bulk endpoint that accepts tokens instead of input texts for each address
    *
    * @return reduced info on found addresses
    */
  def bulkDebug(limitperaddress: Option[String], startDate: Option[String] = None, endDate: Option[String] = None,
                historical: Option[String] = None, matchthreshold: Option[String] = None): Action[BulkBodyDebug] = Action(
    parse.json[BulkBodyDebug]) { implicit request =>

    logger.info(s"#bulkDebugQuery with ${request.body.addresses.size} items")

    val hist = historical match {
      case Some(x) => Try(x.toBoolean).getOrElse(true)
      case None => true
    }

    val startDateVal = startDate.getOrElse("")
    val endDateVal = endDate.getOrElse("")

    val defLimit = conf.config.bulk.limitperaddress
    val limval = limitperaddress.getOrElse(defLimit.toString)
    val limitInt = Try(limval.toInt).toOption.getOrElse(defLimit)

    val defThreshold = conf.config.bulk.matchThreshold
    val threshval = matchthreshold.getOrElse(defThreshold.toString)
    val thresholdFloat = Try(threshval.toFloat).toOption.getOrElse(defThreshold)

    logger.info("threshold = " + thresholdFloat)

    val result: Option[Result] =
      batchValidation.validateBatchSource
        .orElse(batchValidation.validateBatchStartDate(startDateVal))
        .orElse(batchValidation.validateBatchEndDate(endDateVal))
        .orElse(batchValidation.validateBatchKeyStatus)
        .orElse(batchValidation.validateBatchAddressLimit(Some(limval)))
        .orElse(batchValidation.validateBatchThreshold(matchthreshold))
        .orElse(None)

    result match {

      case Some(res) =>
        res

      case _ =>
        val requestsData: Stream[BulkAddressRequestData] = request.body.addresses.toStream.map {
          row => BulkAddressRequestData(row.id, row.tokens.values.mkString(" "), row.tokens)
        }

        val configOverwrite: Option[QueryParamsConfig] = request.body.config

        bulkQuery(requestsData, configOverwrite, Some(limitInt), includeFullAddress = false,
          startDate = startDateVal, endDate = endDateVal, historical = hist, thresholdFloat)
    }
  }

  /**
    * Iterates over requests data and adapts the size of the bulk-chunks using back-pressure.
    * ES rejects requests when it cannot handle them (because of the consumed resources)
    * in this case we reduce the bulk size so that we could process the data successfully.
    * Otherwise we increase the size of the bulk so that we could do more in one bulk
    *
    * It should throw an exception if the situation is desperate (we only do one request at
    * a time and this request fails)
    *
    * @param requests          Stream of data that will be used to query ES
    * @param miniBatchSize     the size of the bulk to use
    * @param configOverwrite   optional configuration that will overwrite current queryParam
    * @param canUpScale        wether or not this particular iteration can upscale the mini-batch size
    * @param successfulResults accumulator of successfull results
    * @return Queried addresses
    */
  @tailrec
  final def iterateOverRequestsWithBackPressure(
    requests: Stream[BulkAddressRequestData],
    miniBatchSize: Int,
    limitperaddress: Option[Int] = None,
    configOverwrite: Option[QueryParamsConfig] = None,
    startDate: String,
    endDate: String,
    historical: Boolean,
    matchThreshold: Float,
    includeFullAddress: Boolean = false,
    clusterid: String = "",
    canUpScale: Boolean = true,
    successfulResults: Stream[Seq[AddressBulkResponseAddress]] = Stream.empty): Stream[Seq[AddressBulkResponseAddress]] = {

    logger.systemLog(batchSize = miniBatchSize.toString)

    val defaultBatchSize = conf.config.bulk.batch.perBatch
    val bulkSizeWarningThreshold = conf.config.bulk.batch.warningThreshold

    if (miniBatchSize < defaultBatchSize * bulkSizeWarningThreshold)
      logger.warn(s"#bulkQuery mini-bulk size it less than a ${defaultBatchSize * bulkSizeWarningThreshold}: size = $miniBatchSize , check if everything is fine with ES")
    else
      logger.info(s"#bulkQuery sending a mini-batch of the size $miniBatchSize")

    val miniBatch = requests.take(miniBatchSize)
    val requestsAfterMiniBatch = requests.drop(miniBatchSize)
    val addressesPerAddress = limitperaddress.getOrElse(conf.config.bulk.limitperaddress)

    val result: BulkAddresses = Await.result(queryBulkAddresses(
      miniBatch, addressesPerAddress, configOverwrite, startDate, endDate, historical, matchThreshold, includeFullAddress, clusterid
    ), Duration.Inf)

    val requestsLeft = requestsAfterMiniBatch ++ result.failedRequests

    if (requestsLeft.isEmpty)
      successfulResults ++ result.successfulBulkAddresses
    else if (miniBatchSize == 1 && result.failedRequests.nonEmpty)
      throw new Exception(
        s"""
           Bulk query request: mini-bulk was scaled down to the size of 1 and it still fails, something's wrong with ES.
           Last request failure message: ${result.failedRequests.head.lastFailExceptionMessage}
        """
      )
    else {
      val miniBatchUpscale = conf.config.bulk.batch.upscale
      val miniBatchDownscale = conf.config.bulk.batch.downscale
      val newMiniBatchSize =
        if (result.failedRequests.isEmpty && canUpScale) math.ceil(miniBatchSize * miniBatchUpscale).toInt
        else if (result.failedRequests.isEmpty) miniBatchSize
        else math.floor(miniBatchSize * miniBatchDownscale).toInt

      val nextCanUpScale = canUpScale && result.failedRequests.isEmpty

      iterateOverRequestsWithBackPressure(
        requestsLeft, newMiniBatchSize, limitperaddress, configOverwrite, startDate, endDate, historical, matchThreshold,
        includeFullAddress, clusterid, nextCanUpScale, successfulResults ++ result.successfulBulkAddresses
      )
    }
  }

  /**
    * Requests addresses for each tokens sequence supplied.
    * This method should not be in `Repository` because it uses `queryAddress`
    * that needs to be mocked through dependency injection
    *
    * @param inputs an iterator containing a collection of tokens per each lines,
    *               typically a result of a parser applied to `Source.fromFile("/path").getLines`
    * @return BulkAddresses containing successful addresses and other information
    */
  def queryBulkAddresses(
    inputs: Stream[BulkAddressRequestData],
    limitperaddress: Int,
    configOverwrite: Option[QueryParamsConfig] = None,
    startDate: String, endDate: String,
    historical: Boolean,
    matchThreshold: Float,
    includeFullAddress: Boolean = false,
    clusterid: String = ""
  ): Future[BulkAddresses] = {

    val bulkAddresses: Future[Stream[Either[BulkAddressRequestData, Seq[AddressBulkResponseAddress]]]] = esRepo.queryBulk(
      inputs, limitperaddress, startDate, endDate, configOverwrite, historical, matchThreshold, includeFullAddress, clusterid
    )

    val successfulAddresses: Future[Stream[Seq[AddressBulkResponseAddress]]] = bulkAddresses.map(
      collectSuccessfulAddresses
    )

    val failedAddresses: Future[Stream[BulkAddressRequestData]] = bulkAddresses.map(collectFailedAddresses)

    // transform (Future[X], Future[Y]) into Future[Z[X, Y]]
    for {
      successful <- successfulAddresses
      failed <- failedAddresses
    } yield BulkAddresses(successful, failed)
  }

  private def requestDataFromRequest(request: Request[BulkBody]): Stream[BulkAddressRequestData] = request.body.addresses.toStream.map {
    row => BulkAddressRequestData(row.id, row.address, parser.parse(row.address))
  }

  private def bulkQuery(
    requestData: Stream[BulkAddressRequestData],
    configOverwrite: Option[QueryParamsConfig],
    limitperaddress: Option[Int],
    includeFullAddress: Boolean = false,
    startDate: String, endDate: String,
    historical: Boolean,
    matchThreshold: Float,
    clusterid: String = ""
  )(implicit request: Request[_]): Result = {

    val startingTime = System.currentTimeMillis()

    val defaultBatchSize = conf.config.bulk.batch.perBatch
    val resultLimit = limitperaddress.getOrElse(conf.config.bulk.limitperaddress)
    val results: Stream[Seq[AddressBulkResponseAddress]] = iterateOverRequestsWithBackPressure(
      requestData, defaultBatchSize, Some(resultLimit), configOverwrite, startDate, endDate,
      historical, matchThreshold, includeFullAddress, clusterid
    )

    logger.info("#bulkQuery processed")

    val bulkItems = results.flatMap {
      addresses =>
        addresses
    }

    val response =
      jsonOk(
        AddressBulkResponseContainer(
          apiVersion = apiVersion,
          dataVersion = dataVersion,
          bulkAddresses = bulkItems,
          status = OkAddressResponseStatus
        )
      )

    val responseTime = System.currentTimeMillis() - startingTime
    val networkid = if (request.headers.get("authorization").getOrElse("Anon").indexOf("+") > 0) request.headers.get("authorization").getOrElse("Anon").split("\\+")(0) else request.headers.get("authorization").getOrElse("Anon").split("_")(0)
    val organisation =  if (request.headers.get("authorization").getOrElse("Anon").indexOf("+") > 0) request.headers.get("authorization").getOrElse("Anon").split("\\+")(0).split("_")(1) else "not set"

    logger.systemLog(
      ip = request.remoteAddress, url = request.uri, responseTimeMillis = responseTime.toString,
      bulkSize = requestData.size.toString, networkid = networkid, organisation = organisation,
      clusterid = clusterid
    )

    response
  }

  private def collectSuccessfulAddresses(addresses: Stream[Either[BulkAddressRequestData,
    Seq[AddressBulkResponseAddress]]]): Stream[Seq[AddressBulkResponseAddress]] =
    addresses.collect {
      case Right(bulkAddresses) => bulkAddresses
    }

  private def collectFailedAddresses(addresses: Stream[Either[BulkAddressRequestData,
    Seq[AddressBulkResponseAddress]]]): Stream[BulkAddressRequestData] =
    addresses.collect {
      case Left(address) => address
    }
}
