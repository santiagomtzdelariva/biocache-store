package au.org.ala.biocache.index

import org.apache.commons.lang.time.DateUtils
import org.slf4j.LoggerFactory
import scala.collection.mutable.ArrayBuffer
import java.util.Date
import org.apache.commons.lang.time.DateFormatUtils
import java.io.{File, FileWriter, OutputStream}
import scala.util.parsing.json.JSON
import au.org.ala.biocache.processor.Processors
import au.org.ala.biocache.dao.OccurrenceDAO
import au.org.ala.biocache.parser.DateParser
import au.org.ala.biocache.Config
import au.org.ala.biocache.load.FullRecordMapper
import au.org.ala.biocache.vocab.AssertionCodes
import au.org.ala.biocache.vocab.{AssertionStatus}
import au.org.ala.biocache.util.Json
import au.org.ala.biocache.model.QualityAssertion

/**
  * All Index implementations need to extend this trait.
  */
trait IndexDAO {

  val logger = LoggerFactory.getLogger("IndexDAO")

  def getRowKeysForQuery(query: String, limit: Int = 1000): Option[List[String]]

  def getUUIDsForQuery(query: String, limit: Int = 1000): Option[List[String]]

  def writeRowKeysToStream(query: String, outputStream: OutputStream)

  def writeUUIDsToStream(query: String, outputStream: OutputStream)

  def occurrenceDAO: OccurrenceDAO

  def getDistinctValues(query: String, field: String, max: Int): Option[List[String]]

  def pageOverFacet(proc: (String, Int) => Boolean, facetName: String, query: String, filterQueries: Array[String])

  def pageOverIndex(proc: java.util.Map[String, AnyRef] => Boolean, fieldToRetrieve: Array[String], query: String, filterQueries: Array[String], sortField: Option[String] = None, sortDir: Option[String] = None, multivaluedFields: Option[Array[String]] = None)

  def streamIndex(proc: java.util.Map[String,AnyRef] => Boolean, fieldsToRetrieve:Array[String], query:String, filterQueries: Array[String], sortFields: Array[String],multivaluedFields: Option[Array[String]] = None)

  def shouldIncludeSensitiveValue(dr: String): Boolean

  /**
    * Index a record with the supplied properties.
    */
  def indexFromMap(guid: String,
                   map: scala.collection.Map[String, String],
                   batch: Boolean = true,
                   startDate: Option[Date] = None,
                   commit: Boolean = false,
                   miscIndexProperties: Seq[String] = Array[String](),
                   test:Boolean = false,
                   batchID:String = "",
                   csvFileWriter:FileWriter = null,
                   csvFileWriterSensitive:FileWriter = null)

  /**
    * Truncate the current index
    */
  def emptyIndex

  def reload

  def shutdown

  def optimise : String

  def commit

  def init

  /**
    * Remove all the records with the specified value in the specified field
    */
  def removeFromIndex(field: String, values: String)

  /** Deletes all the records that satisfy the supplied query */
  def removeByQuery(query: String, commit: Boolean = true)

  /**
    * Perform
    */
  def finaliseIndex(optimise: Boolean = false, shutdown: Boolean = true)

  def getValue(field: String, map: scala.collection.Map[String, String]): String = map.getOrElse(field, "")

  def getValue(field: String, map: scala.collection.Map[String, String], checkParsed: Boolean): String = {
    val value = getValue(field, map)
    if (value == "" && checkParsed) {
      getValue(field + ".p", map)
    } else {
      value
    }
  }

  /**
    * Returns a lat,long string expression formatted to the supplied Double format
    */
  def getLatLongString(lat: Double, lon: Double, format: String): String = {
    if (!lat.isNaN && !lon.isNaN) {
      val df = new java.text.DecimalFormat(format)
      //By some "strange" decision the default rounding model is HALF_EVEN
      df.setRoundingMode(java.math.RoundingMode.HALF_UP)
      df.format(lat) + "," + df.format(lon)
    } else {
      ""
    }
  }

  /**
    * Returns a lat,long string expression formatted to the supplied Double format and step size.
    *
    * e.g. 0.02 step size
    *
    */
  def getLatLongStringStep(lat: Double, lon: Double, format: String, step: Double): String = {
    if (!lat.isNaN && !lon.isNaN) {
      val df = new java.text.DecimalFormat(format)
      //By some "strange" decision the default rounding model is HALF_EVEN
      df.setRoundingMode(java.math.RoundingMode.HALF_UP)
      df.format(Math.round(lat / step) * step) + "," + df.format(Math.round(lon / step) * step)
    } else {
      ""
    }
  }

  /**
    * The header values for the CSV file.
    */
  lazy val header = List("id", "row_key", "occurrence_id", "data_hub_uid", "data_hub", "data_provider_uid", "data_provider", "data_resource_uid",
    "data_resource", "institution_uid", "institution_code", "institution_name",
    "collection_uid", "collection_code", "collection_name", "catalogue_number",
    "taxon_concept_lsid", "occurrence_date", "occurrence_date_end_dt", "occurrence_year", "occurrence_decade_i", "taxon_name", "common_name", "names_and_lsid", "common_name_and_lsid",
    "rank", "rank_id", "raw_taxon_name", "raw_common_name", "multimedia", "image_url", "all_image_url",
    "species_group", "country_code", "country", "lft", "rgt", "kingdom", "phylum", "class", "order",
    "family", "genus", "genus_guid", "species", "species_guid", "state", "places", "latitude", "longitude",
    "lat_long", "point-1", "point-0.1", "point-0.01", "point-0.02", "point-0.001", "point-0.0001",
    "year", "month", "basis_of_record", "raw_basis_of_record", "type_status",
    "raw_type_status", "taxonomic_kosher", "geospatial_kosher",  "location_remarks",
    "occurrence_remarks", "user_assertions", "collector", "state_conservation", "raw_state_conservation", "country_conservation", "raw_country_conservation",
    "sensitive", "coordinate_uncertainty", "user_id", "alau_user_id", "provenance", "subspecies_guid", "subspecies_name", "interaction", "last_assertion_date",
    "last_load_date", "last_processed_date", "modified_date", "establishment_means", "loan_number", "loan_identifier", "loan_destination",
    "loan_botanist", "loan_date", "loan_return_date", "original_name_usage", "duplicate_inst", "record_number", "first_loaded_date", "name_match_metric",
    "life_stage", "outlier_layer", "outlier_layer_count", "taxonomic_issue", "raw_identification_qualifier", "identification_qualifier_s", "species_habitats",
    "identified_by", "identified_date", "sensitive_longitude", "sensitive_latitude", "pest_flag_s", "collectors", "duplicate_status", "duplicate_record",
    "duplicate_type", "sensitive_coordinate_uncertainty", "distance_outside_expert_range", "elevation_d", "min_elevation_d", "max_elevation_d",
    "depth_d", "min_depth_d", "max_depth_d", "name_parse_type_s","occurrence_status_s", "occurrence_details", "photographer_s", "rights",
    "raw_geo_validation_status_s", "raw_occurrence_status_s", "raw_locality","raw_latitude","raw_longitude","raw_datum","raw_sex",
    "sensitive_locality", "event_id", "location_id", "dataset_name", "reproductive_condition_s") ::: Config.additionalFieldsToIndex

  /**
    * sensitive csv header columns
    */
  val sensitiveHeader = List("sensitive_longitude", "sensitive_latitude", "sensitive_coordinate_uncertainty", "sensitive_locality")

  /**
    * Constructs a scientific name.
    *
    * TODO Factor this out of indexing logic, and have a separate field in cassandra that stores this.
    * TODO Construction of this field can then happen as part of the processing.
    */
  def getRawScientificName(map: scala.collection.Map[String, String]): String = {
    val scientificName: String = {
      if (map.contains("scientificName")) {
        map.get("scientificName").get
      } else if (map.contains("genus")) {
        var tmp: String = map.get("genus").get
        if (map.contains("specificEpithet") || map.contains("species")) {
          tmp = tmp + " " + map.getOrElse("specificEpithet", map.getOrElse("species", ""))
          if (map.contains("infraspecificEpithet") || map.contains("subspecies"))
            tmp = tmp + " " + map.getOrElse("infraspecificEpithet", map.getOrElse("subspecies", ""))
        }
        tmp
      } else {
        map.getOrElse("family", "")
      }
    }
    scientificName
  }

  /**
    * Generates an string array version of the occurrence model.
    *
    * Access to the values are taken directly from the Map with no reflection. This
    * should result in a quicker load time.
    */
  def getOccIndexModel(guid: String, map: scala.collection.Map[String, String]) : List[String] = {

    try {
      //get the lat lon values so that we can determine all the point values
      val deleted = map.getOrElse(FullRecordMapper.deletedColumn, "false")
      //only add it to the index is it is not deleted & not a blank record
      if (!deleted.equals("true") && map.size > 1) {
        var slat = getValue("decimalLatitude.p", map)
        var slon = getValue("decimalLongitude.p", map)
        var latlon = ""
        val sciName = getValue("scientificName.p", map)
        val taxonConceptId = getValue("taxonConceptID.p", map)
        val vernacularName = getValue("vernacularName.p", map).trim
        val kingdom = getValue("kingdom.p", map)
        val family = getValue("family.p", map)
        val images = {
          val simages = getValue("images", map)
          if (!simages.isEmpty)
            Json.toStringArray(simages)
          else
            Array[String]()
        }
        //determine the type of multimedia that is available.
        val multimedia: Array[String] = {
          val i = map.getOrElse("images", "[]")
          val s = map.getOrElse("sounds", "[]")
          val v = map.getOrElse("videos", "[]")
          val ab = new ArrayBuffer[String]
          if (i.length() > 3) ab += "Image"
          if (s.length() > 3) ab += "Sound"
          if (v.length() > 3) ab += "Video"
          if (!ab.isEmpty) {
            ab.toArray
          } else {
            Array("None")
          }
        }
        val speciesGroup = {
          val sspeciesGroup = getValue("speciesGroups.p", map)
          if (sspeciesGroup.length > 0)
            Json.toStringArray(sspeciesGroup)
          else
            Array[String]()
        }
        val interactions = {
          if (map.contains("interactions.p"))
            Json.toStringArray(map.get("interactions.p").get)
          else
            Array[String]()
        }
        val dataHubUids = {
          val sdatahubs = getValue("dataHubUid", map, true)
          if (sdatahubs.length > 0)
            Json.toStringArray(sdatahubs)
          else
            Array[String]()
        }
        val habitats = {
          val shab = map.getOrElse("speciesHabitats.p", "[]")
          Json.toStringArray(shab)
        }

        var eventDate = getValue("eventDate.p", map)
        var eventDateEnd = getValue("eventDateEnd.p", map)
        var occurrenceYear = getValue("year.p", map)
        var occurrenceDecade = ""
        if (occurrenceYear.length == 4) {
          occurrenceYear += "-01-01T00:00:00Z"
          occurrenceDecade = occurrenceYear.substring(0,3) + "0"
        } else
          occurrenceYear = ""
        //only want to include eventDates that are in the correct format
        try {
          DateUtils.parseDate(eventDate, Array("yyyy-MM-dd"))
        } catch {
          case e: Exception => eventDate = ""
        }
        //only want to include eventDateEnds that are in the correct format
        try {
          DateUtils.parseDate(eventDateEnd, Array("yyyy-MM-dd"))
        } catch {
          case e: Exception => eventDateEnd = ""
        }
        var lat = java.lang.Double.NaN
        var lon = java.lang.Double.NaN

        if (slat != "" && slon != "") {
          try {
            lat = java.lang.Double.parseDouble(slat)
            lon = java.lang.Double.parseDouble(slon)
            val test = -90D
            val test2 = -180D
            //ensure that the lat longs are in the required range before
            if (lat <= 90 && lat >= test && lon <= 180 && lon >= test2) {
              latlon = slat + "," + slon
            }
          } catch {
            //If the latitude or longitude can't be parsed into a double we don't want to index the values
            case e: Exception => slat = ""; slon = ""
          }
        }
        //get sensitive values map
        val sensitiveMap = {
          if (shouldIncludeSensitiveValue(map.getOrElse("dataResourceUid", "")) && map.contains("originalSensitiveValues")) {
            try {
              val osv = map.getOrElse("originalSensitiveValues", "{}")
              val parsed = JSON.parseFull(osv)
              parsed.get.asInstanceOf[Map[String, String]]
            } catch {
              case _:Exception => Map[String, String]()
            }
          } else {
            Map[String, String]()
          }
        }
        val sconservation = getValue("stateConservation.p", map)
        var stateCons = if (sconservation != "") sconservation.split(",")(0) else ""
        val rawStateCons = if (sconservation != "") sconservation.split(",")(1) else ""

        if (stateCons == "null") stateCons = rawStateCons

        val cconservation = getValue("countryConservation.p", map)
        var countryCons = if (cconservation != "") cconservation.split(",")(0) else ""
        val rawCountryCons = if (cconservation != "") cconservation.split(",")(1) else ""

        if (countryCons == "null") countryCons = rawCountryCons

        val sensitive: String = {
          val dataGen = map.getOrElse("dataGeneralizations.p", "")
          if (dataGen.contains("already generalised"))
            "alreadyGeneralised"
          else if (dataGen != "")
            "generalised"
          else
            ""
        }

        val outlierForLayers: Array[String] = {
          val outlierForLayerStr = getValue("outlierForLayers.p", map)
          if (outlierForLayerStr != "") {
            try {
              Json.toStringArray(outlierForLayerStr)
            } catch {
              case e:Exception => logger.warn(e.getMessage + " : " + guid); Array[String]()
            }
          }
          else Array()
        }

        val dupTypes: Array[String] = {
          val s = map.getOrElse("duplicationType.p", "[]")
          try {
            Json.toStringArray(s)
          } catch {
            case e:Exception => logger.warn(e.getMessage + " : " + guid); Array[String]()
          }
        }

        //Only set the geospatially kosher field if there are coordinates supplied
        val geoKosher = if (slat == "" && slon == "") "" else map.getOrElse(FullRecordMapper.geospatialDecisionColumn, "")
        //val hasUserAss = map.getOrElse(FullRecordMapper.userQualityAssertionColumn, "")
        val userAssertionStatus: Int = map.getOrElse(FullRecordMapper.userAssertionStatusColumn, AssertionStatus.QA_NONE.toString).toInt
        val hasUserAss:String = userAssertionStatus match {
          case AssertionStatus.QA_NONE => "false"
          case _ => userAssertionStatus.toString
        }

        val (subspeciesGuid, subspeciesName): (String, String) = {
          if (map.contains("taxonRankID.p")) {
            try {
              if (java.lang.Integer.parseInt(map.getOrElse("taxonRankID.p", "")) > 7000)
                (taxonConceptId, sciName)
              else
                ("", "")
            } catch {
              case _:Exception => ("", "")
            }
          } else {
            ("", "")
          }
        }

        val lastLoaded = DateParser.parseStringToDate(getValue(FullRecordMapper.alaModifiedColumn, map))

        val lastProcessed = DateParser.parseStringToDate(getValue(FullRecordMapper.alaModifiedColumn + ".p", map))

        val lastUserAssertion = DateParser.parseStringToDate(map.getOrElse(FullRecordMapper.lastUserAssertionDateColumn, ""))

        val firstLoadDate = DateParser.parseStringToDate(getValue("firstLoaded", map))

        val loanDate = DateParser.parseStringToDate(map.getOrElse("loanDate", ""))

        val loanReturnDate = DateParser.parseStringToDate(map.getOrElse("loanReturnDate", ""))

        val dateIdentified = DateParser.parseStringToDate(map.getOrElse("dateIdentified.p", ""))

        val modifiedDate = DateParser.parseStringToDate(map.getOrElse("modified.p", ""))

        var taxonIssue = map.getOrElse("taxonomicIssue.p", "[]")
        if(!taxonIssue.startsWith("[")){
          logger.warn("WARNING " + map.getOrElse("rowKey","") +" does not have an updated taxonIssue: " + guid)
          taxonIssue = "[]"
        }
        val taxonIssueArray = Json.toStringArray(taxonIssue)
        val infoWith = map.getOrElse("informationWithheld.p", "")
        val pest_tmp = if (infoWith.contains("\t")) infoWith.substring(0, infoWith.indexOf("\t")) else ""//startsWith("PEST")) "PEST" else ""

        var distanceOutsideExpertRange = map.getOrElse("distanceOutsideExpertRange.p", "");
        //only want valid numbers
        try {
          distanceOutsideExpertRange.toDouble
        } catch {
          case e: Exception => distanceOutsideExpertRange = ""
        }

        //the returned list needs to match up with the CSV header
        return List(
          getValue("uuid", map),
          getValue("rowKey", map),
          getValue("occurrenceID", map),
          dataHubUids.mkString("|"),
          getValue("dataHub.p", map),
          getValue("dataProviderUid", map, true),
          getValue("dataProviderName", map, true),
          getValue("dataResourceUid", map, true),
          getValue("dataResourceName", map, true),
          getValue("institutionUid.p", map),
          getValue("institutionCode", map),
          getValue("institutionName.p", map),
          getValue("collectionUid.p", map),
          getValue("collectionCode", map),
          getValue("collectionName.p", map),
          getValue("catalogNumber", map),
          taxonConceptId,
          if (eventDate != "") eventDate + "T00:00:00Z" else "",
          if (eventDateEnd != "") eventDateEnd + "T00:00:00Z" else "",
          occurrenceYear,
          occurrenceDecade,
          sciName,
          vernacularName,
          sciName + "|" + taxonConceptId + "|" + vernacularName + "|" + kingdom + "|" + family,
          vernacularName + "|" + sciName + "|" + taxonConceptId + "|" + vernacularName + "|" + kingdom + "|" + family,
          getValue("taxonRank.p", map),
          getValue("taxonRankID.p", map),
          getRawScientificName(map),
          getValue("vernacularName", map),
          //if (!images.isEmpty && images(0) != "") "Multimedia" else "None",
          multimedia.mkString("|"),
          if (!images.isEmpty) images(0) else "",
          images.mkString("|"),
          speciesGroup.mkString("|"),
          getValue("countryCode", map),
          getValue("country.p", map),
          getValue("left.p", map),
          getValue("right.p", map),
          kingdom,
          getValue("phylum.p", map),
          getValue("classs.p", map),
          getValue("order.p", map),
          family,
          getValue("genus.p", map),
          map.getOrElse("genusID.p", ""),
          getValue("species.p", map),
          getValue("speciesID.p", map),
          map.getOrElse("stateProvince.p", ""),
          getValue("lga.p", map),
          slat,
          slon,
          latlon,
          getLatLongString(lat, lon, "#"),
          getLatLongString(lat, lon, "#.#"),
          getLatLongString(lat, lon, "#.##"),
          getLatLongStringStep(lat, lon, "#.##", 0.02),
          getLatLongString(lat, lon, "#.###"),
          getLatLongString(lat, lon, "#.####"),
          getValue("year.p", map),
          getValue("month.p", map),
          getValue("basisOfRecord.p", map),
          getValue("basisOfRecord", map),
          getValue("typeStatus.p", map),
          getValue("typeStatus", map),
          getValue(FullRecordMapper.taxonomicDecisionColumn, map),
          geoKosher,
          //NC 2013-05-23: Assertions are now values, failed, passed and untested these will be handled separately
          //getAssertions(map).mkString("|"),
          getValue("locationRemarks", map),
          getValue("occurrenceRemarks", map),
          hasUserAss,
          //  userAssertionStatus,
          getValue("recordedBy", map),
          stateCons, //stat
          rawStateCons,
          countryCons,
          rawCountryCons,
          sensitive,
          getValue("coordinateUncertaintyInMeters.p", map),
          map.getOrElse("userId", ""),
          map.getOrElse("userId", ""),
          map.getOrElse("provenance.p", ""),
          subspeciesGuid,
          subspeciesName,
          interactions.mkString("|"),
          if (lastUserAssertion.isEmpty) "" else DateFormatUtils.format(lastUserAssertion.get, "yyyy-MM-dd'T'HH:mm:ss'Z'"),
          if (lastLoaded.isEmpty) "2010-11-1T00:00:00Z" else DateFormatUtils.format(lastLoaded.get, "yyyy-MM-dd'T'HH:mm:ss'Z'"),
          if (lastProcessed.isEmpty) "" else DateFormatUtils.format(lastProcessed.get, "yyyy-MM-dd'T'HH:mm:ss'Z'"),
          if (modifiedDate.isEmpty) "" else DateFormatUtils.format(modifiedDate.get,"yyy-MM-dd'T'HH:mm:ss'Z'"),
          map.getOrElse("establishmentMeans.p", "").replaceAll("; ", "|"),
          map.getOrElse("loanSequenceNumber", ""),
          map.getOrElse("loanIdentifier", ""),
          map.getOrElse("loanDestination", ""),
          map.getOrElse("loanForBotanist", ""),
          if (loanDate.isEmpty) "" else DateFormatUtils.format(loanDate.get, "yyyy-MM-dd'T'HH:mm:ss'Z'"),
          if (loanReturnDate.isEmpty) "" else DateFormatUtils.format(loanReturnDate.get, "yyyy-MM-dd'T'HH:mm:ss'Z'"),
          map.getOrElse("originalNameUsage", map.getOrElse("typifiedName", "")),
          map.getOrElse("duplicates", ""), //.replaceAll(",","|"),
          map.getOrElse("recordNumber", ""),
          if (firstLoadDate.isEmpty) "" else DateFormatUtils.format(firstLoadDate.get, "yyyy-MM-dd'T'HH:mm:ss'Z'"),
          map.getOrElse("nameMatchMetric.p", ""),
          map.getOrElse("phenology", ""), //TODO make this a controlled vocab that gets mapped during processing...
          outlierForLayers.mkString("|"),
          outlierForLayers.length.toString,
          taxonIssueArray.mkString("|"),
          map.getOrElse("identificationQualifier", ""),
          map.getOrElse("identificationQualifier.p", ""),
          habitats.mkString("|"),
          map.getOrElse("identifiedBy", ""),
          if (dateIdentified.isEmpty) "" else DateFormatUtils.format(dateIdentified.get, "yyyy-MM-dd'T'HH:mm:ss'Z'"),
          sensitiveMap.getOrElse("decimalLongitude", ""),
          sensitiveMap.getOrElse("decimalLatitude", ""),
          pest_tmp,
          map.getOrElse("recordedBy.p", ""),
          map.getOrElse("duplicationStatus.p", ""),
          map.getOrElse("associatedOccurrences.p", ""),
          dupTypes.mkString("|"),
          sensitiveMap.getOrElse("coordinateUncertaintyInMeters.p", ""),
          distanceOutsideExpertRange,
          map.getOrElse("verbatimElevation.p", ""),
          map.getOrElse("minimumElevationInMeters.p", ""),
          map.getOrElse("maximumElevationInMeters.p", ""),
          map.getOrElse("verbatimDepth.p", ""),
          map.getOrElse("minimumDepthInMeters.p", ""),
          map.getOrElse("maximumDepthInMeters.p", ""),
          map.getOrElse("nameParseType.p",""),
          map.getOrElse("occurrenceStatus.p",""),
          map.getOrElse("occurrenceDetails",""),
          map.getOrElse("photographer",""),
          map.getOrElse("rights",""),
          map.getOrElse("georeferenceVerificationStatus",""),
          map.getOrElse("occurrenceStatus", ""),
          map.getOrElse("locality",""),
          map.getOrElse("decimalLatitude",""),
          map.getOrElse("decimalLongitude",""),
          map.getOrElse("geodeticDatum",""),
          map.getOrElse("sex",""),
          sensitiveMap.getOrElse("locality", ""),
          map.getOrElse("eventID",""),
          map.getOrElse("locationID",""),
          map.getOrElse("datasetName",""),
          map.getOrElse("reproductiveCondition", "")
        ) ::: Config.additionalFieldsToIndex.map(field => map.getOrElse(field, ""))
      } else {
        return List()
      }
    } catch {
      case e: Exception => logger.error(e.getMessage, e); throw e
    }
  }

  def getCsvWriter(sensitive : Boolean = false) = {
    var fw : FileWriter = null
    if (!sensitive && Config.exportIndexAsCsvPath != null && Config.exportIndexAsCsvPath.length > 0) {
      fw = new FileWriter(File.createTempFile("index.", "." + System.currentTimeMillis() + ".csv", new File(Config.exportIndexAsCsvPath)))
    }
    if (sensitive && Config.exportIndexAsCsvPathSensitive != null && Config.exportIndexAsCsvPathSensitive.length > 0) {
      fw = new FileWriter(File.createTempFile("index.sensitive.", "." + System.currentTimeMillis() + ".csv", new File(Config.exportIndexAsCsvPathSensitive)))
    }
    fw
  }
}

/**
  * An class for handling a generic/common index fields
  *
  * Not in use yet.
  */
case class IndexField(fieldName: String, dataType: String, sourceField: String, multi: Boolean = false, storeAsArray: Boolean = false, extraField: Option[String] = None, isMiscProperty: Boolean = false) {

  def getValuesForIndex(map: Map[String, String]): (String, Option[Array[String]]) = {

    //get the source value. Cater for the situation where we get the parsed value if raw doesn't exist
    val sourceValue: String = if (sourceField.contains(",")) {
      //There are multiple fields that supply the source for the field
      val fields = sourceField.split(",")
      fields.foldLeft("")((concat, value) => concat + "|" + map.getOrElse(value, ""))
    } else {
      map.getOrElse(sourceField, if (extraField.isDefined) map.getOrElse(extraField.get, "") else "")
    }

    dataType match {
      case "date" => {
        val date = DateParser.parseStringToDate(sourceValue)
        if (date.isDefined)
          return (fieldName, Some(Array(DateFormatUtils.format(date.get, "yyyy-MM-dd'T'HH:mm:ss'Z'"))))
      }
      case "double" => {
        //needs to be a valid double
        try {
          java.lang.Double.parseDouble(sourceValue)
          return (fieldName, Some(Array(sourceValue)))
        } catch {
          case _:Exception => (fieldName, None)
        }
      }
      case _ => {
        if (sourceValue.length > 0) {
          if (multi && storeAsArray) {
            val array = Json.toStringArray(sourceValue)
            return (fieldName, Some(array))
          }
          if (multi) {
            return (fieldName, Some(sourceValue.split(",")))
          }
        }
      }
    }
    (fieldName, None)
  }
}

object IndexFields {

  val logger = LoggerFactory.getLogger("IndexFields")

  val fieldList = loadFromFile

  val indexFieldMap = fieldList.map(indexField => {
    (indexField.fieldName -> indexField.sourceField)
  }).toMap

  val storeFieldMap = fieldList.map(indexField => {
    (indexField.sourceField -> indexField.fieldName)
  }).toMap

  val storeMiscFields = fieldList collect {
    case value if value.isMiscProperty => value.sourceField
  }

  def loadFromFile() = {
    scala.io.Source.fromURL(getClass.getResource("/indexFields.txt"), "utf-8").getLines.toList.collect {
      case row if !row.startsWith("#") && row.split("\t").length == 7 => {
        val values = row.split("\t")
        new IndexField(values(0),
          values(1),
          values(2),
          "T" == values(3),
          "T" == values(4),
          if (values(5).size > 0) Some(values(5)) else None, "T" == values(6)
        )
      }
    }
  }
}