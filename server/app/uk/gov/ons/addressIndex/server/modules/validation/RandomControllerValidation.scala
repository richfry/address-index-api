package uk.gov.ons.addressIndex.server.modules.validation

import javax.inject.{Inject, Singleton}
import play.api.mvc.Result
import uk.gov.ons.addressIndex.model.server.response.address.{FilterInvalidError, MixedFilterError}
import uk.gov.ons.addressIndex.server.modules.response.RandomControllerResponse
import uk.gov.ons.addressIndex.server.modules.{ConfigModule, VersionModule}

import scala.concurrent.Future
import scala.util.Try

@Singleton
class RandomControllerValidation @Inject()(implicit conf: ConfigModule, versionProvider: VersionModule)
  extends Validation with RandomControllerResponse {

  def validateRandomFilter(classificationfilter: Option[String]): Option[Future[Result]] = {

    val filterString: String = classificationfilter.getOrElse("")

    if (!filterString.isEmpty){
      if (filterString.contains("*") && filterString.contains(",")){
        logger.systemLog(badRequestMessage = MixedFilterError.message)
        Some(futureJsonBadRequest(RandomMixedFilter))
      }
      else if (!filterString.matches("""\b(residential|commercial|C|c|C\w+|c\w+|L|l|L\w+|l\w+|M|m|M\w+|m\w+|O|o|O\w+|o\w+|P|p|P\w+|p\w+|R|r|R\w+|r\w+|U|u|U\w+|u\w+|X|x|X\w+|x\w+|Z|z|Z\w+|z\w+)\b.*""")) {
        logger.systemLog(badRequestMessage = FilterInvalidError.message)
        Some(futureJsonBadRequest(RandomFilterInvalid))
      } else None
    } else None

  }
}
