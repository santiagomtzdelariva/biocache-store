package au.org.ala.biocache.processor

import java.util

import org.slf4j.LoggerFactory
import au.org.ala.biocache._
import au.org.ala.sds.SensitiveDataService
import scala.collection.JavaConversions
import scala.collection.mutable.ArrayBuffer
import org.apache.commons.lang.StringUtils
import org.geotools.referencing.crs.DefaultGeographicCRS
import org.geotools.referencing.CRS
import org.geotools.referencing.operation.DefaultCoordinateOperationFactory
import org.geotools.geometry.GeneralDirectPosition
import org.apache.commons.math3.util.Precision
import au.org.ala.biocache.caches.{SensitiveAreaDAO, TaxonProfileDAO, LocationDAO}
import au.org.ala.biocache.parser.{DistanceRangeParser, VerbatimLatLongParser}
import au.org.ala.biocache.model._
import au.org.ala.biocache.load.FullRecordMapper
import au.org.ala.biocache.vocab._
import au.org.ala.biocache.util.{GISUtil, GridUtil, Json, StringHelper}

/**
 * Processor of location information.
 */
class LocationProcessor extends Processor {

  import StringHelper._
  import AssertionCodes._
  import AssertionStatus._
  import JavaConversions._
  import au.org.ala.sds.util._

  val logger = LoggerFactory.getLogger("LocationProcessor")


  //This is being initialised here because it may take some time to load all the XML records...
  val sds = new SensitiveDataService()

  lazy val crsEpsgCodesMap = {
    var valuesMap = Map[String, String]()
    for (line <- scala.io.Source.fromURL(getClass.getResource("/crsEpsgCodes.txt"), "utf-8").getLines().toList) {
      val values = line.split('=')
      valuesMap += (values(0) -> values(1))
    }
    valuesMap
  }

  lazy val zoneEpsgCodesMap = {
    var valuesMap = Map[String, String]()
    for (line <- scala.io.Source.fromURL(getClass.getResource("/zoneEpsgCodes.txt"), "utf-8").getLines().toList) {
      val values = line.split('=')
      valuesMap += (values(0) -> values(1))
    }
    valuesMap
  }

  /**
   * Process geospatial details.
   *
   * We will need to parse a variety of formats. Bryn was going to find some regular
   * expressions/test cases he has used previously...
   */
  def process(guid: String, raw: FullRecord, processed: FullRecord, lastProcessed: Option[FullRecord] = None): Array[QualityAssertion] = {

    logger.debug("Processing location for guid: " + guid)

    //retrieve the point
    val assertions = new ArrayBuffer[QualityAssertion]

    //handle the situation where the coordinates have already been sensitised
    setProcessedCoordinates(raw, processed, assertions)

    //parse altitude and depth values
    processAltitudeAndDepth(guid, raw, processed, assertions)

    //Continue processing location if a processed longitude and latitude exists
    if (processed.location.decimalLatitude != null && processed.location.decimalLongitude != null) {

      //validate the coordinate values
      validateCoordinatesValues(raw, processed, assertions)

      //validate coordinate accuracy (coordinateUncertaintyInMeters) and coordinatePrecision (precision - A. Chapman)
      checkCoordinateUncertainty(raw, processed, assertions)
    }

    //sensitise the coordinates if necessary.  Do this last so that habitat checks
    // etc are performed on originally supplied coordinates
    if(Config.sdsEnabled){
      processSensitivity(raw, processed)
    }

    //more checks
    if (processed.location.decimalLatitude != null && processed.location.decimalLongitude != null) {

      //intersect values with sensitive areas
      val intersectValues = SensitiveAreaDAO.intersect(processed.location.decimalLongitude, processed.location.decimalLatitude)

      //add state province, country, LGA
      processed.location.stateProvince = intersectValues.getOrElse(Config.stateProvinceLayerID, null)
      processed.location.lga  = intersectValues.getOrElse(Config.localGovLayerID, null)
      processed.location.country = intersectValues.getOrElse(Config.countriesLayerID, null)
      if (processed.location.country == null && processed.location.stateProvince != null) {
        processed.location.country = Config.defaultCountry
      }

      //habitat, no standard vocab available
      processed.location.habitat = raw.location.habitat

      //add the layers that are associated with the point
      processed.location.biome = {
        if (intersectValues.getOrElse(Config.terrestrialLayerID, null) != null) "Terrestrial"
        else if (intersectValues.getOrElse(Config.marineLayerID, null) != null) "Marine"
        else null
      }

      //check matched stateProvince
      checkForStateMismatch(raw, processed, assertions)

      //add the conservation status if necessary
      addConservationStatus(raw, processed)

      //check marine/non-marine
      checkForHabitatMismatch(raw, processed, assertions)
    }

    //add point sampling
    val point = LocationDAO.getByLatLon(processed.location.decimalLatitude, processed.location.decimalLongitude)
    if (!point.isEmpty) {
      val (location, environmentalLayers, contextualLayers) = point.get
      processed.locationDetermined = true
      //add state information
      processed.el = environmentalLayers
      processed.cl = contextualLayers
    }

    //create flag if no location info was supplied for this record
    checkLocationSupplied(raw, processed, assertions)

    //run validation tests against the processed coordinates
    validateCoordinates(raw, processed, assertions)

    //process state/country values if coordinates not determined
    processStateCountryValues(raw, processed, assertions)

    //validate the geo-reference values
    validateGeoreferenceValues(raw, processed, assertions)

    //return the assertions created by this processor
    assertions.toArray
  }

  /**
   * Create flag if no location info was supplied for this record
   *
   * @param raw
   * @param processed
   * @param assertions
   * @return
   */
  def checkLocationSupplied(raw: FullRecord, processed: FullRecord, assertions: ArrayBuffer[QualityAssertion]): ArrayBuffer[QualityAssertion] = {
    if (processed.location.decimalLatitude == null || processed.location.decimalLongitude == null) {
      //check to see if we have any location information at all for the record
      if (raw.location.footprintWKT == null && raw.location.locality == null && raw.location.locationID == null) {
        assertions += QualityAssertion(LOCATION_NOT_SUPPLIED)
      } else {
        assertions += QualityAssertion(LOCATION_NOT_SUPPLIED, PASSED)
      }
    } else {
      assertions += QualityAssertion(LOCATION_NOT_SUPPLIED, PASSED)
    }
  }

  /**
   * If no coordinates have been supplied, parse raw state and country values to vocabularies.
   *
   * @param raw
   * @param processed
   * @param assertions
   */
  private def processStateCountryValues(raw: FullRecord, processed: FullRecord, assertions: ArrayBuffer[QualityAssertion]){

    //Only process the raw state value if no latitude and longitude is provided
    if (processed.location.stateProvince == null && raw.location.decimalLatitude == null && raw.location.decimalLongitude == null) {
      //process the supplied state
      val stateTerm = StateProvinces.matchTerm(raw.location.stateProvince)
      if (!stateTerm.isEmpty) {
        processed.location.stateProvince = stateTerm.get.canonical
        //now check for sensitivity based on state
        if(Config.sdsEnabled) {
          processSensitivity(raw, processed)
        }
        processed.location.country = StateProvinceToCountry.map.getOrElse(processed.location.stateProvince, "")
      }
    }

    //Only process the raw country value if no latitude and longitude is provided
    if (processed.location.country == null && raw.location.decimalLatitude == null && raw.location.decimalLongitude == null) {
      //process the supplied state
      val countryTerm = Countries.matchTerm(raw.location.country)
      if (!countryTerm.isEmpty) {
        processed.location.country = countryTerm.get.canonical
      }
    }

    //Try the country code
    if( processed.location.country == null && raw.location.countryCode != null){
      val countryCodeTerm = Countries.matchTerm(raw.location.countryCode)
      if (!countryCodeTerm.isEmpty) {
        processed.location.country = countryCodeTerm.get.canonical
      }
    }
  }

  /**
   * Validation checks
   *
   * @param raw
   * @param processed
   * @param assertions
   */
  private def validateCoordinates(raw: FullRecord, processed: FullRecord, assertions: ArrayBuffer[QualityAssertion]): Unit = {
    if (raw.location.country == null && processed.location.country != null) {
      assertions += QualityAssertion(COUNTRY_INFERRED_FROM_COORDINATES, FAILED)
    } else {
      assertions += QualityAssertion(COUNTRY_INFERRED_FROM_COORDINATES, PASSED)
    }

    //check centre point of the state
    if (StateProvinceCentrePoints.coordinatesMatchCentre(processed.location.stateProvince, raw.location.decimalLatitude, raw.location.decimalLongitude)) {
      assertions += QualityAssertion(COORDINATES_CENTRE_OF_STATEPROVINCE, "Coordinates are centre point of " + processed.location.stateProvince)
    } else {
      assertions += QualityAssertion(COORDINATES_CENTRE_OF_STATEPROVINCE, PASSED)
    }

    //check centre point of the country
    if (CountryCentrePoints.coordinatesMatchCentre(processed.location.country, raw.location.decimalLatitude, raw.location.decimalLongitude)) {
      assertions += QualityAssertion(COORDINATES_CENTRE_OF_COUNTRY, "Coordinates are centre point of " + processed.location.country)
    } else {
      assertions += QualityAssertion(COORDINATES_CENTRE_OF_COUNTRY, PASSED)
    }
  }

  /**
   * Performs the QAs associated with elevation and depth
   */
  private def processAltitudeAndDepth(guid: String, raw: FullRecord, processed: FullRecord, assertions: ArrayBuffer[QualityAssertion]) {
    //check that the values are numeric
    processVerbatimDepth(raw, processed, assertions)
    processVerbatimElevation(raw, processed, assertions)
    processMinMaxDepth(raw, processed, assertions)
  }

  private def processMinMaxDepth(raw: FullRecord, processed: FullRecord, assertions: ArrayBuffer[QualityAssertion]): Unit = {
    //check for max and min reversals
    if (raw.location.minimumDepthInMeters != null && raw.location.maximumDepthInMeters != null) {
      try {
        val min = raw.location.minimumDepthInMeters.toFloat
        val max = raw.location.maximumDepthInMeters.toFloat
        if (min > max) {
          processed.location.minimumDepthInMeters = max.toString
          processed.location.maximumDepthInMeters = min.toString
          assertions += QualityAssertion(MIN_MAX_DEPTH_REVERSED, "The minimum, " + min + ", and maximum, " + max + ", depths have been transposed.")
        } else {
          processed.location.minimumDepthInMeters = min.toString
          processed.location.maximumDepthInMeters = max.toString
          assertions += QualityAssertion(MIN_MAX_DEPTH_REVERSED, PASSED)
        }
      }
      catch {
        case e: Exception => logger.debug("Exception thrown processing minimumDepthInMeters:" + e.getMessage())
      }
    }

    if (raw.location.minimumElevationInMeters != null && raw.location.maximumElevationInMeters != null) {
      try {
        val min = raw.location.minimumElevationInMeters.toFloat
        val max = raw.location.maximumElevationInMeters.toFloat
        if (min > max) {
          processed.location.minimumElevationInMeters = max.toString
          processed.location.maximumElevationInMeters = min.toString
          assertions += QualityAssertion(MIN_MAX_ALTITUDE_REVERSED, "The minimum, " + min + ", and maximum, " + max + ", elevations have been transposed.")
        } else {
          processed.location.minimumElevationInMeters = min.toString
          processed.location.maximumElevationInMeters = max.toString
          assertions += QualityAssertion(MIN_MAX_ALTITUDE_REVERSED, PASSED)
        }
      } catch {
        case e: Exception => logger.debug("Exception thrown processing elevation:" + e.getMessage())
      }
    }
  }

  private def processVerbatimElevation(raw: FullRecord, processed: FullRecord, assertions: ArrayBuffer[QualityAssertion]): Unit = {
    if (raw.location.verbatimElevation != null) {
      val parseElevationResult = DistanceRangeParser.parse(raw.location.verbatimElevation)
      if (parseElevationResult.isDefined) {
        val (velevation, sourceUnit) = parseElevationResult.get
        processed.location.verbatimElevation = velevation.toString
        if (velevation > 10000 || velevation < -100) {
          assertions += QualityAssertion(ALTITUDE_OUT_OF_RANGE, s"Elevation $velevation is greater than 10,000 metres or less than -100 metres.")
        } else {
          assertions += QualityAssertion(ALTITUDE_OUT_OF_RANGE, PASSED)
        }
        assertions += QualityAssertion(ALTITUDE_NON_NUMERIC, PASSED)

        if (sourceUnit == Feet) {
          assertions += QualityAssertion(ALTITUDE_IN_FEET, "The supplied altitude was in feet it has been converted to metres")
        } else {
          assertions += QualityAssertion(ALTITUDE_IN_FEET, PASSED)
        }
      } else {
        assertions += QualityAssertion(ALTITUDE_NON_NUMERIC, "Can't parse verbatimElevation " + raw.location.verbatimElevation)
      }
    }
  }

  private def processVerbatimDepth(raw: FullRecord, processed: FullRecord, assertions: ArrayBuffer[QualityAssertion]): Unit = {
    if (raw.location.verbatimDepth != null) {
      val parseDepthResult = DistanceRangeParser.parse(raw.location.verbatimDepth)
      if (parseDepthResult.isDefined) {
        val (vdepth, sourceUnit) = parseDepthResult.get
        processed.location.verbatimDepth = vdepth.toString
        if (vdepth > 10000)
          assertions += QualityAssertion(DEPTH_OUT_OF_RANGE, s"Depth $vdepth is greater than 10,000 metres")
        else
          assertions += QualityAssertion(DEPTH_OUT_OF_RANGE,  PASSED)
        assertions += QualityAssertion(DEPTH_NON_NUMERIC,  PASSED)
        //check on the units
        if (sourceUnit == Feet) {
          assertions += QualityAssertion(DEPTH_IN_FEET, "The supplied depth was in feet it has been converted to metres")
        } else {
          assertions += QualityAssertion(DEPTH_IN_FEET, PASSED)
        }
      } else {
        assertions += QualityAssertion(DEPTH_NON_NUMERIC, "Can't parse verbatimDepth " + raw.location.verbatimDepth)
      }
    }
  }

  private def setProcessedCoordinates(raw: FullRecord, processed: FullRecord, assertions: ArrayBuffer[QualityAssertion]) {

    //handle the situation where the coordinates have already been sensitised
    if (raw.location.originalDecimalLatitude != null && raw.location.originalDecimalLongitude != null) {
      processed.location.decimalLatitude = raw.location.originalDecimalLatitude
      processed.location.decimalLongitude = raw.location.originalDecimalLongitude
      processed.location.verbatimLatitude = raw.location.originalVerbatimLatitude
      processed.location.verbatimLongitude = raw.location.originalVerbatimLongitude
      //set the raw values too
      raw.location.decimalLatitude = raw.location.originalDecimalLatitude
      raw.location.decimalLongitude = raw.location.originalDecimalLongitude

    } else {
      //use raw values
      val gisPointOption = processLatLong(
        raw.location.decimalLatitude,
        raw.location.decimalLongitude,
        raw.location.geodeticDatum,
        raw.location.verbatimLatitude,
        raw.location.verbatimLongitude,
        raw.location.verbatimSRS,
        raw.location.easting,
        raw.location.northing,
        raw.location.zone,
        raw.location.gridReference,
        assertions)

      gisPointOption match {
        case Some(gisPoint) => {
          processed.location.decimalLatitude = gisPoint.latitude
          processed.location.decimalLongitude = gisPoint.longitude
          processed.location.geodeticDatum = gisPoint.datum
          processed.location.coordinateUncertaintyInMeters = gisPoint.coordinateUncertaintyInMeters
          processed.location.bbox = gisPoint.minLatitude + "," + gisPoint.minLongitude + "," + gisPoint.maxLatitude + "," + gisPoint.maxLongitude
          processed.location.northing = gisPoint.northing
          processed.location.easting = gisPoint.easting
        }
        case None => //do nothing
      }
    }
  }

  /**
   * Process the latitude, longitude converting raw coordinates to decimal latitude, longitude.
   * Handles reprojections where required.
   *
   * @param rawLatitude
   * @param rawLongitude
   * @param rawGeodeticDatum
   * @param verbatimLatitude
   * @param verbatimLongitude
   * @param verbatimSRS
   * @param easting
   * @param northing
   * @param zone
   * @param assertions
   * @return
   */
  def processLatLong(rawLatitude: String, rawLongitude: String, rawGeodeticDatum: String, verbatimLatitude: String,
                     verbatimLongitude: String, verbatimSRS: String, easting: String, northing: String, zone: String,
                     gridReference:String, assertions: ArrayBuffer[QualityAssertion]): Option[GISPoint] = {

    //check to see if we have coordinates specified
    if (rawLatitude != null && rawLongitude != null && !rawLatitude.toFloatWithOption.isEmpty && !rawLongitude.toFloatWithOption.isEmpty) {
      processDecimalCoordinates(rawLatitude, rawLongitude, rawGeodeticDatum, assertions)
      // Attempt to infer the decimal latitude and longitude from the verbatim latitude and longitude
    } else {
      //no decimal latitude/longitude was provided
      assertions += QualityAssertion(DECIMAL_COORDINATES_NOT_SUPPLIED)
      if (verbatimLatitude != null && verbatimLongitude != null) {
        var decimalVerbatimLat = verbatimLatitude.toFloatWithOption
        var decimalVerbatimLong = verbatimLongitude.toFloatWithOption

        if (decimalVerbatimLat.isEmpty || decimalVerbatimLong.isEmpty) {
          //parse the expressions into their decimal equivalents
          decimalVerbatimLat = VerbatimLatLongParser.parse(verbatimLatitude)
          decimalVerbatimLong = VerbatimLatLongParser.parse(verbatimLongitude)
        }

        if (!decimalVerbatimLat.isEmpty && !decimalVerbatimLong.isEmpty) {
          processVerbatimCoordinates(verbatimSRS, assertions, decimalVerbatimLat, decimalVerbatimLong)
        } else {
          None
        }
      } else if (easting != null && northing != null && zone != null) {
        processNorthingEastingZone(verbatimSRS, easting, northing, zone, assertions)
      } else if ( gridReference != null) {
        val result = GridUtil.processGridReference(gridReference)
        if(!result.isEmpty){
          assertions += QualityAssertion(DECIMAL_LAT_LONG_CALCULATED_FROM_GRID_REF)
        }
        result
      } else {
        None
      }
    }
  }

  /**
   * Process the raw string values supplied as decimal latitude and longitude.
   *
   * @param rawLatitude
   * @param rawLongitude
   * @param rawGeodeticDatum
   * @param assertions
   * @return
   */
  private def processDecimalCoordinates(rawLatitude: String, rawLongitude: String, rawGeodeticDatum: String,
                                        assertions: ArrayBuffer[QualityAssertion]): Option[GISPoint] = {

    //coordinates were supplied so the test passed
    assertions += QualityAssertion(DECIMAL_COORDINATES_NOT_SUPPLIED, PASSED)
    // if decimal lat/long is provided in a CRS other than WGS84, then we need to reproject

    if (rawGeodeticDatum != null) {
      //no assumptions about the datum is being made:
      assertions += QualityAssertion(GEODETIC_DATUM_ASSUMED_WGS84, PASSED)
      val sourceEpsgCode = lookupEpsgCode(rawGeodeticDatum)
      if (!sourceEpsgCode.isEmpty) {
        //datum is recognised so pass the test:
        assertions += QualityAssertion(UNRECOGNIZED_GEODETIC_DATUM, PASSED)
        if (sourceEpsgCode.get == GISUtil.WGS84_EPSG_Code) {
          //already in WGS84, no need to reproject
          Some(GISPoint(rawLatitude, rawLongitude, GISUtil.WGS84_EPSG_Code, null))
        } else {
          // Reproject decimal lat/long to WGS84
          val desiredNoDecimalPlaces = math.min(getNumberOfDecimalPlacesInDouble(rawLatitude), getNumberOfDecimalPlacesInDouble(rawLongitude))

          val reprojectedCoords = GISUtil.reprojectCoordinatesToWGS84(
            rawLatitude.toDouble,
            rawLongitude.toDouble,
            sourceEpsgCode.get,
            desiredNoDecimalPlaces
          )

          if (reprojectedCoords.isEmpty) {
            assertions += QualityAssertion(DECIMAL_LAT_LONG_CONVERSION_FAILED, "Transformation of decimal latiude and longitude to WGS84 failed")
            None
          } else {
            //transformation of coordinates did not fail:
            assertions += QualityAssertion(DECIMAL_LAT_LONG_CONVERSION_FAILED, PASSED)
            assertions += QualityAssertion(DECIMAL_LAT_LONG_CONVERTED, "Decimal latitude and longitude were converted to WGS84 (EPSG:4326)")
            val (reprojectedLatitude, reprojectedLongitude) = reprojectedCoords.get
            Some(GISPoint(reprojectedLatitude, reprojectedLongitude, GISUtil.WGS84_EPSG_Code, null))
          }
        }
      } else {
        assertions += QualityAssertion(UNRECOGNIZED_GEODETIC_DATUM, s"Geodetic datum $rawGeodeticDatum not recognized.")
        Some(GISPoint(rawLatitude, rawLongitude, rawGeodeticDatum, null))
      }
    } else {
      //assume coordinates already in WGS84
      assertions += QualityAssertion(GEODETIC_DATUM_ASSUMED_WGS84, "Geodetic datum assumed to be WGS84 (EPSG:4326)")
      Some(GISPoint(rawLatitude, rawLongitude, GISUtil.WGS84_EPSG_Code, null))
    }
  }

  /**
   * Process verbatim coordinate values.
   *
   * @param verbatimSRS
   * @param assertions
   * @param decimalVerbatimLat
   * @param decimalVerbatimLong
   * @return
   */
  private def processVerbatimCoordinates(verbatimSRS: String, assertions: ArrayBuffer[QualityAssertion],
                                 decimalVerbatimLat: Option[Float], decimalVerbatimLong: Option[Float]): Option[GISPoint] = {
    if (decimalVerbatimLat.get.toString.isLatitude && decimalVerbatimLong.get.toString.isLongitude) {

      // If a verbatim SRS is supplied, reproject coordinates to WGS 84
      if (verbatimSRS != null) {
        val sourceEpsgCode = lookupEpsgCode(verbatimSRS)
        if (!sourceEpsgCode.isEmpty) {
          //calculation from verbatim did NOT fail:
          assertions += QualityAssertion(DECIMAL_LAT_LONG_CALCULATION_FROM_VERBATIM_FAILED, PASSED)
          if (sourceEpsgCode.get == GISUtil.WGS84_EPSG_Code) {
            //already in WGS84, no need to reproject
            assertions += QualityAssertion(DECIMAL_LAT_LONG_CALCULATED_FROM_VERBATIM,
              "Decimal latitude and longitude were calculated using verbatimLatitude, verbatimLongitude and verbatimSRS")
            Some(GISPoint(decimalVerbatimLat.get.toString, decimalVerbatimLong.get.toString, GISUtil.WGS84_EPSG_Code, null))
          } else {

            val desiredNoDecimalPlaces = math.min(
              getNumberOfDecimalPlacesInDouble(decimalVerbatimLat.get.toString),
              getNumberOfDecimalPlacesInDouble(decimalVerbatimLong.get.toString)
            )

            val reprojectedCoords = GISUtil.reprojectCoordinatesToWGS84(decimalVerbatimLat.get, decimalVerbatimLong.get, sourceEpsgCode.get, desiredNoDecimalPlaces)
            if (reprojectedCoords.isEmpty) {
              assertions += QualityAssertion(DECIMAL_LAT_LONG_CALCULATION_FROM_VERBATIM_FAILED,
                "Transformation of verbatim latiude and longitude to WGS84 failed")
              None
            } else {
              //reprojection did NOT fail:
              assertions += QualityAssertion(DECIMAL_LAT_LONG_CALCULATION_FROM_VERBATIM_FAILED, PASSED)
              assertions += QualityAssertion(DECIMAL_LAT_LONG_CALCULATED_FROM_VERBATIM,
                "Decimal latitude and longitude were calculated using verbatimLatitude, verbatimLongitude and verbatimSRS")
              val (reprojectedLatitude, reprojectedLongitude) = reprojectedCoords.get
              Some(GISPoint(reprojectedLatitude, reprojectedLongitude, GISUtil.WGS84_EPSG_Code, null))
            }
          }
        } else {
          assertions += QualityAssertion(DECIMAL_LAT_LONG_CALCULATION_FROM_VERBATIM_FAILED, "Unrecognized verbatimSRS " + verbatimSRS)
          None
        }
        // Otherwise, assume latitude and longitude are already in WGS 84
      } else if (decimalVerbatimLat.get.toString.isLatitude && decimalVerbatimLong.get.toString.isLongitude) {
        //conversion dod NOT fail
        assertions += QualityAssertion(DECIMAL_LAT_LONG_CALCULATION_FROM_VERBATIM_FAILED, PASSED)
        assertions += QualityAssertion(DECIMAL_LAT_LONG_CALCULATED_FROM_VERBATIM,
          "Decimal latitude and longitude were calculated using verbatimLatitude, verbatimLongitude and verbatimSRS")
        Some(GISPoint(decimalVerbatimLat.get.toString, decimalVerbatimLong.get.toString, GISUtil.WGS84_EPSG_Code, null))
      } else {
        // Invalid latitude, longitude
        assertions += QualityAssertion(DECIMAL_LAT_LONG_CALCULATION_FROM_VERBATIM_FAILED, "Could not parse verbatim latitude and longitude")
        None
      }
    } else {
      None
    }
  }

  /**
   * Converts a easting northing to a decimal latitude/longitude.
   *
   * @param verbatimSRS
   * @param easting
   * @param northing
   * @param zone
   * @param assertions
    * @return 3-tuple reprojectedLatitude, reprojectedLongitude, WGS84_EPSG_Code
   */
  private def processNorthingEastingZone(verbatimSRS: String, easting: String, northing: String, zone: String,
                                     assertions: ArrayBuffer[QualityAssertion]): Option[GISPoint] = {

    // Need a datum and a zone to get an epsg code for transforming easting/northing values
    val epsgCodeKey = {
      if (verbatimSRS != null) {
        verbatimSRS.toUpperCase + "|" + zone
      } else {
        // Assume GDA94 / MGA zone
        "GDA94|" + zone
      }
    }

    if (zoneEpsgCodesMap.contains(epsgCodeKey)) {
      val crsEpsgCode = zoneEpsgCodesMap(epsgCodeKey)
      val eastingAsDouble = easting.toDoubleWithOption
      val northingAsDouble = northing.toDoubleWithOption

      if (!eastingAsDouble.isEmpty && !northingAsDouble.isEmpty) {
        // Always round to 5 decimal places as easting/northing values are in metres and 0.00001 degree is approximately equal to 1m.
        val reprojectedCoords = GISUtil.reprojectCoordinatesToWGS84(eastingAsDouble.get, northingAsDouble.get, crsEpsgCode, 5)
        if (reprojectedCoords.isEmpty) {
          assertions += QualityAssertion(DECIMAL_LAT_LONG_CALCULATION_FROM_EASTING_NORTHING_FAILED,
            "Transformation of verbatim easting and northing to WGS84 failed")
          None
        } else {
          //lat and long from easting and northing did NOT fail:
          assertions += QualityAssertion(DECIMAL_LAT_LONG_CALCULATION_FROM_EASTING_NORTHING_FAILED, PASSED)
          assertions += QualityAssertion(DECIMAL_LAT_LONG_CALCULATED_FROM_EASTING_NORTHING,
            "Decimal latitude and longitude were calculated using easting, northing and zone.")
          val (reprojectedLatitude, reprojectedLongitude) = reprojectedCoords.get
          Some(GISPoint(reprojectedLatitude, reprojectedLongitude, GISUtil.WGS84_EPSG_Code, null))
        }
      } else {
        None
      }
    } else {
      if (verbatimSRS == null) {
        assertions += QualityAssertion(DECIMAL_LAT_LONG_CALCULATION_FROM_EASTING_NORTHING_FAILED,
          "Unrecognized zone GDA94 / MGA zone " + zone)
      } else {
        assertions += QualityAssertion(DECIMAL_LAT_LONG_CALCULATION_FROM_EASTING_NORTHING_FAILED,
          "Unrecognized zone " + verbatimSRS + " / zone " + zone)
      }
      None
    }
  }

  /**
   * Get the number of decimal places in a double value in string form
    *
    * @param decimalAsString
   * @return
   */
   def getNumberOfDecimalPlacesInDouble(decimalAsString: String): Int = {
    val tokens = decimalAsString.split('.')
    if (tokens.length == 2) {
      tokens(1).length
    } else {
      0
    }
  }

  /**
   * Get the EPSG code associated with a coordinate reference system string e.g. "WGS84" or "AGD66".
    *
    * @param crs The coordinate reference system string.
   * @return The EPSG code associated with the CRS, or None if no matching code could be found.
   *         If the supplied string is already a valid EPSG code, it will simply be returned.
   */
  private def lookupEpsgCode(crs: String): Option[String] = {
    if (StringUtils.startsWithIgnoreCase(crs, "EPSG:")) {
      // Do a lookup with the EPSG code to ensure that it is valid
      try {
        CRS.decode(crs.toUpperCase)
        // lookup was successful so just return the EPSG code
        Some(crs.toUpperCase)
      } catch {
        case ex: Exception => None
      }
    } else if (crsEpsgCodesMap.contains(crs.toUpperCase)) {
      Some(crsEpsgCodesMap(crs.toUpperCase()))
    } else {
      None
    }
  }

  private def checkCoordinateUncertainty(raw: FullRecord, processed: FullRecord, assertions: ArrayBuffer[QualityAssertion]) {
    //validate coordinate accuracy (coordinateUncertaintyInMeters) and coordinatePrecision (precision - A. Chapman)
    var checkedPrecision =false
    if (raw.location.coordinateUncertaintyInMeters != null && raw.location.coordinateUncertaintyInMeters.length > 0) {
      //parse it into a numeric number in metres
      //TODO should this be a whole number??
      val parsedResult = DistanceRangeParser.parse(raw.location.coordinateUncertaintyInMeters)
      if (!parsedResult.isEmpty) {
        val (parsedValue, rawUnit) = parsedResult.get
        if(parsedValue > 0){
          //not an uncertainty mismatch
          assertions += QualityAssertion(UNCERTAINTY_RANGE_MISMATCH, PASSED)
        } else {
          val comment = "Supplied uncertainty, " + raw.location.coordinateUncertaintyInMeters + ", is not a supported format"
          assertions += QualityAssertion(UNCERTAINTY_RANGE_MISMATCH, comment)
        }
        processed.location.coordinateUncertaintyInMeters = parsedValue.toString
      } else {
        val comment = "Supplied uncertainty, " + raw.location.coordinateUncertaintyInMeters + ", is not a supported format"
        assertions += QualityAssertion(UNCERTAINTY_RANGE_MISMATCH, comment)
      }
    } else {
      //check to see if the uncertainty has incorrectly been put in the precision
      if (raw.location.coordinatePrecision != null) {
        //TODO work out what sort of custom parsing is necessary
        val value = raw.location.coordinatePrecision.toFloatWithOption
        if (!value.isEmpty && value.get > 1) {
          processed.location.coordinateUncertaintyInMeters = value.get.toInt.toString
          val comment = "Supplied precision, " + raw.location.coordinatePrecision + ", is assumed to be uncertainty in metres";
          assertions += QualityAssertion(UNCERTAINTY_IN_PRECISION, comment)
          checkedPrecision = true
        }
      }
    }
    if (raw.location.coordinatePrecision == null){
      assertions += QualityAssertion(MISSING_COORDINATEPRECISION, "Missing coordinatePrecision")
    } else {
      assertions += QualityAssertion(MISSING_COORDINATEPRECISION, PASSED)
      if(!checkedPrecision){
        val value = raw.location.coordinatePrecision.toFloatWithOption
        if(value.isDefined){
          //Ensure that the precision is within the required ranges
          if (value.get > 0 && value.get <= 1){
            assertions += QualityAssertion(PRECISION_RANGE_MISMATCH, PASSED)
            //now test for coordinate precision
            val pre = if (raw.location.coordinatePrecision.contains(".")) raw.location.coordinatePrecision.split("\\.")(1).length else 0
            val lat = processed.location.decimalLatitude
            val long = processed.location.decimalLongitude
            val latp = if(lat.contains(".")) lat.split("\\.")(1).length else 0
            val lonp = if(long.contains(".")) long.split("\\.")(1).length else 0
            if(pre == latp && pre == lonp){
              // no coordinate precision mismatch exists
              assertions += QualityAssertion(COORDINATE_PRECISION_MISMATCH, PASSED)
            } else {
              assertions += QualityAssertion(COORDINATE_PRECISION_MISMATCH)
            }
          } else{
            assertions += QualityAssertion(PRECISION_RANGE_MISMATCH, "Coordinate precision is not between 0 and 1" )
          }
        } else {
           assertions += QualityAssertion(PRECISION_RANGE_MISMATCH, "Unable to parse the coordinate precision")
        }
      }
    }

    // If the coordinateUncertainty is still empty populate it with the default
    // value (we don't test until now because the SDS will sometime include coordinate uncertainty)
    // This step will pick up on default values because processed.location.coordinateUncertaintyInMeters
    // will already be populated if a default value exists
    if (processed.location.coordinateUncertaintyInMeters == null) {
      assertions += QualityAssertion(UNCERTAINTY_NOT_SPECIFIED, "Uncertainty was not supplied")
    } else{
      assertions += QualityAssertion(UNCERTAINTY_NOT_SPECIFIED, PASSED)
    }
  }

  /**
   * Check the habitats for the taxon profile against the biome associated with the point.
   *
   * @param raw
   * @param processed
   * @param assertions
   */
  private def checkForHabitatMismatch(raw: FullRecord, processed: FullRecord, assertions: ArrayBuffer[QualityAssertion]) {

    if (processed.location.biome == null) {
      assertions += QualityAssertion(COORDINATE_HABITAT_MISMATCH, 2)
      return
    }

    //retrieve taxon and genus profiles
    val taxonProfileWithOption = TaxonProfileDAO.getByGuid(processed.classification.taxonConceptID)
    val genusProfileWithOption = TaxonProfileDAO.getByGuid(processed.classification.genusID)
    val habitats = {
      if (!taxonProfileWithOption.isEmpty && taxonProfileWithOption.get.habitats != null && !taxonProfileWithOption.get.habitats.isEmpty) {
        taxonProfileWithOption.get.habitats
      } else if (!genusProfileWithOption.isEmpty && genusProfileWithOption.get.habitats != null && !genusProfileWithOption.get.habitats.isEmpty){
        genusProfileWithOption.get.habitats
      } else {
        Array[String]()
      }
    }

    if (!habitats.isEmpty) {
      val habitatsAsString = habitats.mkString(",")
      val habitatFromPoint = processed.location.biome
      val habitatsForSpecies = habitats
      //is "terrestrial" the same as "non-marine" ??
      val validHabitat = HabitatMap.areTermsCompatible(habitatFromPoint, habitatsForSpecies)
      if (!validHabitat.isEmpty) {
        if (!validHabitat.get) {
          logger.debug("[QualityAssertion] ******** Habitats incompatible for ROWKEY: " + raw.rowKey + ", processed:"
            + processed.location.biome + ", retrieved:" + habitatsAsString
            + ", http://maps.google.com/?ll=" + processed.location.decimalLatitude + ","
            + processed.location.decimalLongitude)
          val comment = "Recognised habitats for species: " + habitatsAsString +
            ", Value determined from coordinates: " + habitatFromPoint
          assertions += QualityAssertion(COORDINATE_HABITAT_MISMATCH, comment)
        } else {
          //habitats ARE compatible
          assertions += QualityAssertion(COORDINATE_HABITAT_MISMATCH, PASSED)
        }
      }
    } else {
      assertions += QualityAssertion(COORDINATE_HABITAT_MISMATCH, UNCHECKED)
    }
  }

  /**
   * Add the correct conservation status to the record.
   *
   * @param raw
   * @param processed
   */
  private def addConservationStatus(raw: FullRecord, processed: FullRecord) {
    //retrieve the species profile
    val taxonProfileWithOption = TaxonProfileDAO.getByGuid(processed.classification.taxonConceptID)
    if(!taxonProfileWithOption.isEmpty){
      val taxonProfile = taxonProfileWithOption.get
      //add the conservation status if necessary
      if (taxonProfile.conservation != null) {
        val country = taxonProfile.retrieveConservationStatus(processed.location.country)
        processed.occurrence.countryConservation = country.getOrElse(null)
        val state = taxonProfile.retrieveConservationStatus(processed.location.stateProvince)
        processed.occurrence.stateConservation = state.getOrElse(null)
        val global = taxonProfile.retrieveConservationStatus("Global")
        processed.occurrence.globalConservation = global.getOrElse(null)
      }
    }
  }

  /**
   * Check the supplied state value aligns with the supplied coordinates.
   *
   * @param raw
   * @param processed
   * @param assertions
   */
  private def checkForStateMismatch(raw: FullRecord, processed: FullRecord, assertions: ArrayBuffer[QualityAssertion]) {
    //check matched stateProvince
    if (processed.location.stateProvince != null && raw.location.stateProvince != null) {
      //quality systemAssertions
      val stateTerm = StateProvinces.matchTerm(raw.location.stateProvince)
      if (!stateTerm.isEmpty && !processed.location.stateProvince.equalsIgnoreCase(stateTerm.get.canonical)) {
        logger.debug("[QualityAssertion] " + raw.rowKey + ", processed:" + processed.location.stateProvince
          + ", raw:" + raw.location.stateProvince)
        //add a quality assertion
        val comment = "Supplied: " + stateTerm.get.canonical + ", calculated: " + processed.location.stateProvince
        assertions += QualityAssertion(STATE_COORDINATE_MISMATCH, comment)
      } else {
        //states are not in mismatch
        assertions += QualityAssertion(STATE_COORDINATE_MISMATCH, PASSED)
      }
    } else {
      assertions += QualityAssertion(STATE_COORDINATE_MISMATCH, UNCHECKED)
    }
  }

  /**
   * Check other geospatial details have been supplied.
   *
   * @param raw
   * @param processed
   * @param assertions
   * @return
   */
  def validateGeoreferenceValues(raw: FullRecord, processed: FullRecord, assertions: ArrayBuffer[QualityAssertion]) = {
    //check for missing geodeticDatum
    if (raw.location.geodeticDatum == null && processed.location.geodeticDatum == null)
      assertions += QualityAssertion(MISSING_GEODETICDATUM, "Missing geodeticDatum")
    else
      assertions += QualityAssertion(MISSING_GEODETICDATUM,PASSED)
    //check for missing georeferencedBy
    if (raw.location.georeferencedBy == null && processed.location.georeferencedBy == null)
      assertions += QualityAssertion(MISSING_GEOREFERNCEDBY, "Missing georeferencedBy")
    else
      assertions += QualityAssertion(MISSING_GEOREFERNCEDBY, PASSED)
    //check for missing georeferencedProtocol
    if (raw.location.georeferenceProtocol == null && processed.location.georeferenceProtocol == null)
      assertions += QualityAssertion(MISSING_GEOREFERENCEPROTOCOL, "Missing georeferenceProtocol")
    else
      assertions += QualityAssertion(MISSING_GEOREFERENCEPROTOCOL,PASSED)
    //check for missing georeferenceSources
    if (raw.location.georeferenceSources == null && processed.location.georeferenceSources == null)
      assertions += QualityAssertion(MISSING_GEOREFERENCESOURCES, "Missing georeferenceSources")
    else
      assertions += QualityAssertion(MISSING_GEOREFERENCESOURCES,PASSED)
    //check for missing georeferenceVerificationStatus
    if (raw.location.georeferenceVerificationStatus == null && processed.location.georeferenceVerificationStatus == null)
      assertions += QualityAssertion(MISSING_GEOREFERENCEVERIFICATIONSTATUS, "Missing georeferenceVerificationStatus")
    else
      assertions += QualityAssertion(MISSING_GEOREFERENCEVERIFICATIONSTATUS,PASSED)
    //check for missing georeferenceDate
    if (StringUtils.isBlank(raw.location.georeferencedDate) && !raw.miscProperties.containsKey("georeferencedDate")){
      assertions += QualityAssertion(MISSING_GEOREFERENCE_DATE)
    } else {
      assertions += QualityAssertion(MISSING_GEOREFERENCE_DATE, PASSED)
    }
  }

  /**
   * Performs a bunch of the coordinate validations
   */
  def validateCoordinatesValues(raw: FullRecord, processed: FullRecord, assertions: ArrayBuffer[QualityAssertion]) = {
    //when the locality is Australia latitude needs to be negative and longitude needs to be positive
    //TO DO fix this so that it uses the gazetteer to determine whether or not coordinates
    val latWithOption = processed.location.decimalLatitude.toFloatWithOption
    val lonWithOption = processed.location.decimalLongitude.toFloatWithOption

    if (!latWithOption.isEmpty && !lonWithOption.isEmpty) {

      val lat = latWithOption.get
      val lon = lonWithOption.get

      //Test that coordinates are in range
      if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
        //test to see if they have been inverted
        if (lon >= -90 && lon <= 90 && lat >= -180 && lat <= 180) {
          assertions += QualityAssertion(INVERTED_COORDINATES, "Assume that coordinates have been inverted. Original values: " +
            processed.location.decimalLatitude + "," + processed.location.decimalLongitude)
          val tmp = processed.location.decimalLatitude
          processed.location.decimalLatitude = processed.location.decimalLongitude
          processed.location.decimalLongitude = tmp
          //coordinates are not out of range:
          assertions += QualityAssertion(COORDINATES_OUT_OF_RANGE, PASSED)
        } else {
          assertions += QualityAssertion(COORDINATES_OUT_OF_RANGE, "Coordinates are out of range: " +
            processed.location.decimalLatitude + "," + processed.location.decimalLongitude)
          assertions += QualityAssertion(INVERTED_COORDINATES,PASSED)
        }
      } else {
        assertions ++= Array(QualityAssertion(INVERTED_COORDINATES,PASSED), QualityAssertion(COORDINATES_OUT_OF_RANGE, PASSED))
      }

      if (lat == 0.0f && lon == 0.0f) {
        assertions += QualityAssertion(ZERO_COORDINATES, "Coordinates 0,0")
        processed.location.decimalLatitude = null
        processed.location.decimalLongitude = null
      } else {
        assertions += QualityAssertion(ZERO_COORDINATES,PASSED)
      }

      if (lat == 0.0f ) {
        assertions += QualityAssertion(AssertionCodes.ZERO_LATITUDE_COORDINATES, "Latitude 0,0")
      } else{
        assertions += QualityAssertion(AssertionCodes.ZERO_LATITUDE_COORDINATES, PASSED)
      }

      if (lon == 0.0f) {
        assertions += QualityAssertion(AssertionCodes.ZERO_LONGITUDE_COORDINATES, "Longitude 0,0")
      } else{
        assertions += QualityAssertion(AssertionCodes.ZERO_LONGITUDE_COORDINATES, PASSED)
      }

      if (raw.location.country != null && raw.location.country != "") {

        val country = Countries.matchTerm(raw.location.country)

        if (!country.isEmpty) {

          assertions += QualityAssertion(UNKNOWN_COUNTRY_NAME, PASSED)

          CountryCentrePoints.matchName(country.get.canonical) match {

            case Some((latlng, bbox)) => {

              if (!bbox.containsPoint(lat, lon)) {

                var hasCoordinateMismatch = true

                if (bbox.containsPoint(lat * -1, lon)) {
                  //latitude is negated
                  assertions += QualityAssertion(NEGATED_LATITUDE,
                    "Latitude seems to be negated. Original value:" + processed.location.decimalLatitude)
                  processed.location.decimalLatitude = (lat * -1).toString
                  hasCoordinateMismatch = false
                }

                if (bbox.containsPoint(lat, lon * -1)) {
                  //point in wrong EW hemisphere - what do we do?
                  assertions += QualityAssertion(NEGATED_LONGITUDE,
                    "Longitude seems to be negated. Original value: " + processed.location.decimalLongitude)
                  processed.location.decimalLongitude = (lon * -1).toString
                  hasCoordinateMismatch = false
                }

                if(hasCoordinateMismatch){
                  assertions += QualityAssertion(COUNTRY_COORDINATE_MISMATCH)
                } else {
                  //there was no mismatch
                  assertions += QualityAssertion(COUNTRY_COORDINATE_MISMATCH, PASSED)
                }

              }
            }
            case _ => //do nothing
          }
        } else {
          assertions += QualityAssertion(UNKNOWN_COUNTRY_NAME, "Country name '" + raw.location.country + "' not recognised.")
        }
      }
    }
  }

  /**
   * Run sensitive data checks and modify the data appropriately.
   *
   * It allows for Pest sensitivity to be reported in the "informationWithheld" field.
   * Rework will be necessary when we work out the best way to handle these.
   */
  private def processSensitivity(raw: FullRecord, processed: FullRecord) : Unit = {

    //needs to be performed for all records whether or not they are in Australia
    //get a map representation of the raw record...
    /************** SDS check ************/
    logger.debug("Starting SDS check")
    val rawMap = scala.collection.mutable.Map[String, String]()
    raw.objectArray.foreach { poso =>
      val map = FullRecordMapper.mapObjectToProperties(poso, Versions.RAW)
      rawMap.putAll(map)
    }

    if(!processed.location.decimalLongitude.toDoubleWithOption.isEmpty && !processed.location.decimalLatitude.toDoubleWithOption.isEmpty){
      //do a dynamic lookup for the layers required for the SDS
      val layerIntersect = SensitiveAreaDAO.intersect(processed.location.decimalLongitude.toDouble,
        processed.location.decimalLatitude.toDouble)

      GeoLocationHelper.getGeospatialLayers.foreach { key =>
        rawMap.put(key, layerIntersect.getOrElse(key, "n/a"))
      }

      val intersectStateProvince = layerIntersect.getOrElse(Config.stateProvinceLayerID, "")

      if(StringUtils.isBlank(intersectStateProvince)){
        val stringMatchState = StateProvinces.matchTerm(raw.location.stateProvince)
        if(!stringMatchState.isEmpty){
          rawMap.put("stateProvince", stringMatchState.get.canonical)
        }
      } else {
        rawMap.put("stateProvince", intersectStateProvince)
      }
    }

    //put the processed event date components in to allow for correct date applications of the rules
    if(processed.event.day != null)
      rawMap("day") = processed.event.day
    if(processed.event.month != null)
      rawMap("month") = processed.event.month
    if(processed.event.year != null)
      rawMap("year") = processed.event.year

    val exact = getExactSciName(raw)
    //now get the ValidationOutcome from the Sensitive Data Service
    val outcome = sds.testMapDetails(Config.sdsFinder, rawMap, exact, processed.classification.taxonConceptID)

    logger.debug("SDS outcome: " + outcome)

    /************** SDS check end ************/

    if (outcome != null && outcome.isValid && outcome.isSensitive) {

      if (outcome.getResult != null) {

        val map: scala.collection.mutable.Map[String, Object] = outcome.getResult

        //convert it to a string string map
        val stringMap = map.collect({
          case (key, value) if value != null => if (key == "originalSensitiveValues") {
            val osv = value.asInstanceOf[java.util.HashMap[String, String]]
            //add the original "processed" coordinate uncertainty to the sensitive values so that it can be available if necessary
            if (processed.location.coordinateUncertaintyInMeters != null) {
              osv.put("coordinateUncertaintyInMeters.p", processed.location.coordinateUncertaintyInMeters)
            }
            //remove all the el/cl's from the original sensitive values
            au.org.ala.sds.util.GeoLocationHelper.getGeospatialLayers.foreach(key => osv.remove(key))
            val newv = Json.toJSON(osv)
            (key -> newv)
          } else {
            (key -> value.toString)
          }
        })

        //take away the values that need to be added to the processed record NOT the raw record
        val uncertainty = stringMap.get("generalisationInMetres")
        if (!uncertainty.isEmpty) {
          //we know that we have sensitised, add the uncertainty to the currently processed uncertainty
          if (StringUtils.isNotEmpty(uncertainty.get.toString)) {

            val currentUncertainty = if (StringUtils.isNotEmpty(processed.location.coordinateUncertaintyInMeters)) {
              java.lang.Float.parseFloat(processed.location.coordinateUncertaintyInMeters)
            } else {
              0
            }

            val newUncertainty = currentUncertainty + java.lang.Integer.parseInt(uncertainty.get.toString)
            processed.location.coordinateUncertaintyInMeters = newUncertainty.toString
          }
          processed.location.decimalLatitude = stringMap.getOrElse("decimalLatitude", "")
          processed.location.decimalLongitude = stringMap.getOrElse("decimalLongitude", "")
          stringMap -= "generalisationInMetres"
        }

        processed.occurrence.informationWithheld = stringMap.getOrElse("informationWithheld", "")
        processed.occurrence.dataGeneralizations = stringMap.getOrElse("dataGeneralizations", "")
        stringMap -= "informationWithheld"
        stringMap -= "dataGeneralizations"

        //remove the day from the values if present
        raw.event.day = ""
        processed.event.day = ""
        processed.event.eventDate = ""
        if (processed.event.eventDateEnd != null) processed.event.eventDateEnd = ""

        //update the raw record with whatever is left in the stringMap - change to use DAO method...
        if(StringUtils.isNotBlank(raw.rowKey)){
          Config.persistenceManager.put(raw.rowKey, "occ", stringMap.toMap, false)
        }

      } else if(!outcome.isLoadable() && Config.obeySDSIsLoadable){
          logger.warn("SDS isLoadable status is currently not being used. Would apply to: " + processed.uuid)
//        //remove all event information
//        raw.event.clearAllProperties
//        raw.location.clearAllProperties
//        Config.persistenceManager.put(raw.rowKey, "occ", raw.location.toMap(true))
//        Config.persistenceManager.put(raw.rowKey, "occ", raw.event.toMap(true))
//
//        processed.event.clearAllProperties
//        processed.location.clearAllProperties
      }

      if(outcome.getReport().getMessages() != null){
        var infoMessage = ""
        outcome.getReport().getMessages().foreach(message => {
          infoMessage += message.getCategory() + "\t" + message.getMessageText() + "\n"
        })
        processed.occurrence.informationWithheld = infoMessage
      }
    } else {
      //Species is NOT sensitive
      //if the raw record has originalSensitive values we need to re-initialise the value
      if (StringUtils.isNotBlank(raw.rowKey) && raw.occurrence.originalSensitiveValues != null && !raw.occurrence.originalSensitiveValues.isEmpty) {
        Config.persistenceManager.put(raw.rowKey, "occ", raw.occurrence.originalSensitiveValues + ("originalSensitiveValues" -> ""), false)
      }
    }
  }

  private def getExactSciName(raw: FullRecord): String = {
    if (raw.classification.scientificName != null)
      raw.classification.scientificName
    else if (raw.classification.subspecies != null)
      raw.classification.subspecies
    else if (raw.classification.species != null)
      raw.classification.species
    else if (raw.classification.genus != null) {
      if (raw.classification.specificEpithet != null) {
        if (raw.classification.infraspecificEpithet != null)
          raw.classification.genus + " " + raw.classification.specificEpithet + " " + raw.classification.infraspecificEpithet
        else
          raw.classification.genus + " " + raw.classification.specificEpithet
      } else {
        raw.classification.genus
      }
    }
    else if (raw.classification.vernacularName != null) // handle the case where only a common name is provided.
      raw.classification.vernacularName
    else //return the name default name string which will be null
      raw.classification.scientificName
  }

  def getName = FullRecordMapper.geospatialQa
}


case class GISPoint(latitude:String, longitude:String, datum:String, coordinateUncertaintyInMeters:String,
                    easting:String = null, northing:String  = null, minLatitude:String = null, minLongitude:String = null, maxLatitude:String = null, maxLongitude:String = null)