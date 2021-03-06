package au.org.ala.biocache

import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import au.org.ala.biocache.model.{Versions, QualityAssertion, FullRecord}
import au.org.ala.biocache.load.FullRecordMapper
import au.org.ala.biocache.vocab.AssertionCodes
import au.org.ala.biocache.vocab.AssertionStatus

@RunWith(classOf[JUnitRunner])
class AssertionCodeTest extends ConfigFunSuite {
  val rowKey = "test1"
  val rowKey2 = "test2"
  val rowKey3 = "test3"
  val uuid = "uuid"
  val occurrenceDAO = Config.occurrenceDAO

  test("Test isGeospatiallyKosher"){
    val expectTrue1 = AssertionCodes.isGeospatiallyKosher(Array(20000))
    expectResult(true){expectTrue1}

    val expectTrue2 = AssertionCodes.isGeospatiallyKosher(Array(1))
    expectResult(true){expectTrue2}

    val expectFalse = AssertionCodes.isGeospatiallyKosher(Array(0, 1))
    expectResult(false){expectFalse}

    val expectTrueOld1 = Array(20000).filter(qa=> {
      val code = AssertionCodes.geospatialCodes.find(c => c.code == qa)
      !code.isEmpty && code.get.isFatal
    }).isEmpty
    expectResult(true){expectTrueOld1}

    val expectTrueOld2 = Array(1).filter(qa=> {
      val code = AssertionCodes.geospatialCodes.find(c => c.code == qa)
      !code.isEmpty && code.get.isFatal
    }).isEmpty
    expectResult(true){expectTrueOld2}

    val expectFalseOld = Array(0, 1).filter(qa=> {
      val code = AssertionCodes.geospatialCodes.find(c => c.code == qa)
      !code.isEmpty && code.get.isFatal
    }).isEmpty
    expectResult(false){expectFalseOld}

  }

  test("Test isTaxonomicallyKosher"){
    val expectTrue1 = AssertionCodes.isTaxonomicallyKosher(Array(0))
    expectResult(true){expectTrue1}

    val expectTrue2 = AssertionCodes.isTaxonomicallyKosher(Array(10000))
    expectResult(true){expectTrue2}

     val expectTrueOld1 = Array(0).filter(qa=> {
      val code = AssertionCodes.taxonomicCodes.find(c => c.code == qa)
      !code.isEmpty && code.get.isFatal
    }).isEmpty
    expectResult(true){expectTrueOld1}

    val expectTrueOld2 = Array(10000).filter(qa=> {
      val code = AssertionCodes.taxonomicCodes.find(c => c.code == qa)
      !code.isEmpty && code.get.isFatal
    }).isEmpty
    expectResult(true){expectTrueOld2}

  }


  test("Test the geospatially kosher test") {

    val assertions1 = Array(QualityAssertion(AssertionCodes.GEOSPATIAL_ISSUE), QualityAssertion(AssertionCodes.INVERTED_COORDINATES))
    expectResult(false) {
      AssertionCodes.isGeospatiallyKosher(assertions1)
    }

    val assertions2 = Array(QualityAssertion(AssertionCodes.INVERTED_COORDINATES))
    expectResult(true) {
      AssertionCodes.isGeospatiallyKosher(assertions2)
    }
  }

  test("Test kosher based on list of int codes") {
    val codes1 = Array(1, 2, 3, 30)
    expectResult(true) {
      AssertionCodes.isGeospatiallyKosher(codes1)
    }
    val codes2 = Array(1, 2, 0)
    expectResult(false) {
      AssertionCodes.isGeospatiallyKosher(codes2)
    }
  }

  /**
   * A user assertion true or false overrides the value of a system assertion
   */
  test("Test User Assertion takes precedence") {
    //rowKey: String, fullRecord: FullRecord, assertions: Option[Map[String,Array[QualityAssertion]]], version: Version
    val processed = new FullRecord
    processed.location.decimalLatitude = "123.123"
    processed.location.decimalLongitude = "123.123"
    processed.rowKey = rowKey
    processed.uuid = uuid
    val assertions = Some(Map("loc" -> Array(QualityAssertion(AssertionCodes.GEOSPATIAL_ISSUE))))
    occurrenceDAO.updateOccurrence(rowKey, processed, assertions, Versions.PROCESSED)
    expectResult(1) {
      occurrenceDAO.getSystemAssertions(rowKey).size
    }
    expectResult(false) {
      val record = occurrenceDAO.getByRowKey(rowKey)
      if (record.isEmpty)
        false
      else {
        record.get.geospatiallyKosher
      }
    }
    //println(Config.persistenceManager)
    //now have a false user assertion counteract this one
    val qa = QualityAssertion(AssertionCodes.VERIFIED, "system", AssertionStatus.QA_VERIFIED)
 //   val qa = QualityAssertion(AssertionCodes.GEOSPATIAL_ISSUE, false)
    qa.comment = "Overrride the system assertion"
    qa.userId = "Natasha.Carter@csiro.au"
    qa.userDisplayName = "Natasha Carter"
    occurrenceDAO.addUserAssertion(rowKey, qa)
    //println(Config.persistenceManager)
    expectResult(true) {
      val record = occurrenceDAO.getByRowKey(rowKey)
      if (record.isEmpty)
        false
      else {
        record.get.geospatiallyKosher
      }
    }
  }

  /**
   * A false user assertion overrides all true user or system assertions (for the same code)
   */
  test("Single false User Assertion takes precedence") {
    val qa1 = QualityAssertion(AssertionCodes.TAXONOMIC_ISSUE, "", AssertionStatus.QA_UNCONFIRMED)
    qa1.comment = "True user assertion"
    qa1.userId = "Natasha.Carter@csiro.au"
    qa1.userDisplayName = "Natasha Carter"
    occurrenceDAO.addUserAssertion(rowKey, qa1)
    //println(Config.persistenceManager)
    expectResult(2) {
      occurrenceDAO.getUserAssertions(rowKey).size
    }
    expectResult(true) {
      val record = occurrenceDAO.getByRowKey(rowKey)
      //println(record.get.assertions.toSet)
      record.get.assertions.contains("taxonomicIssue")
    }
 //   val qa2 = QualityAssertion(AssertionCodes.TAXONOMIC_ISSUE, false)
    val qa2 = QualityAssertion(AssertionCodes.VERIFIED, qa1.uuid, AssertionStatus.QA_VERIFIED)
    qa2.comment = "False user assertion to override"
    qa2.userId = "Natasha.Carter2@csiro.au"
    qa2.userDisplayName = "Natasha Carter"
    occurrenceDAO.addUserAssertion(rowKey, qa2)
    //println(Config.persistenceManager)
    expectResult(3) {
      occurrenceDAO.getUserAssertions(rowKey).size
    }
    expectResult(false) {
      val record = occurrenceDAO.getByRowKey(rowKey)
      record.get.assertions.contains("taxonomicIssue")
    }
  }

  /**
   * A system assertion must take into account the values of the existing user assertions before updating
   */
  test("User Assertions checked during system assertions update") {
    //add user asssertion
    val qa1 = QualityAssertion(AssertionCodes.ID_PRE_OCCURRENCE, "", AssertionStatus.QA_UNCONFIRMED)
    qa1.comment = "Tests user assertion is still applied after system assertions updated"
    qa1.userId = "Natasha.Carter@csiro.au"
    qa1.userDisplayName = "Natasha Carter"
    occurrenceDAO.addUserAssertion(rowKey, qa1)
    val processed = new FullRecord
    processed.location.decimalLatitude = "123.123"
    processed.location.decimalLongitude = "123.123"
    processed.rowKey = rowKey
    processed.uuid = uuid
    val assertions = Some(Map("loc" -> Array(QualityAssertion(AssertionCodes.GEOSPATIAL_ISSUE)), "event" -> Array[QualityAssertion]()))
    occurrenceDAO.updateOccurrence(rowKey, processed, assertions, Versions.PROCESSED)
    expectResult(true) {
      val record = occurrenceDAO.getByRowKey(rowKey)
      record.get.assertions.contains("idPreOccurrence")
    }
    //println(Config.persistenceManager)
    val qa2 = QualityAssertion(AssertionCodes.VERIFIED, qa1.uuid, AssertionStatus.QA_VERIFIED)
    qa2.comment = "False value overrides"
    qa2.userId = "Natasha.Carter2@csiro.au"
    qa2.userDisplayName = "Natasha Carter"
    occurrenceDAO.addUserAssertion(rowKey, qa2)
    expectResult(false) {
      val record = occurrenceDAO.getByRowKey(rowKey)
      record.get.assertions.contains("idPreOccurrence")
    }
    occurrenceDAO.updateOccurrence(rowKey, processed, assertions, Versions.PROCESSED)
    expectResult(false) {
      val record = occurrenceDAO.getByRowKey(rowKey)
      record.get.assertions.contains("idPreOccurrence")
    }
    //println(Config.persistenceManager)
  }

  test("Verify Record") {
    //need to deal with a new record to test the verification...
    val processed = new FullRecord
    processed.location.decimalLatitude = "123.123"
    processed.location.decimalLongitude = "123.123"
    processed.rowKey = rowKey
    processed.uuid = uuid
    val assertions = Some(Map("loc" -> Array(QualityAssertion(AssertionCodes.GEOSPATIAL_ISSUE))))
    occurrenceDAO.updateOccurrence(rowKey2, processed, assertions, Versions.PROCESSED)
    //println(Config.persistenceManager)
    //Test that the record starts off as geospatialKosher =false
    expectResult(false) {
      val record = occurrenceDAO.getByRowKey(rowKey2)
      record.get.geospatiallyKosher
    }
    val vr = QualityAssertion(AssertionCodes.VERIFIED, "system", AssertionStatus.QA_VERIFIED)
    vr.comment = "This record is verified"
    vr.userId = "Natasha.Carter@csiro.au"
    vr.userDisplayName = "Natasha Carter"
    occurrenceDAO.addUserAssertion(rowKey2, vr)
    //println(Config.persistenceManager)
    //test that verifying the records changes the geospatialKosher=true
    expectResult(true) {
      val record = occurrenceDAO.getByRowKey(rowKey2)
      record.get.geospatiallyKosher
    }
    val raw = occurrenceDAO.getByRowKey(rowKey2)
    occurrenceDAO.updateOccurrence(rowKey2, raw.get.createNewProcessedRecord, assertions, Versions.PROCESSED)
    //test that reprocessing a verified record retains the geospatialKosher = true even when applying failing qa
    expectResult(true) {
      val record = occurrenceDAO.getByRowKey(rowKey2)
      //println(record.get.assertions.toList)
      record.get.geospatiallyKosher //&& record.get.assertions.contains("userVerified")

    }
    //test that record 2 only reports back the 1 user assertion
    expectResult(1) {
      occurrenceDAO.getUserAssertions(rowKey2).size
    }
    //test the record 1 reports back the 5 user assertions that have been assigned.
    expectResult(5) {
      occurrenceDAO.getUserAssertions(rowKey).size
    }
  }

  test("user assertion flag") {
    val processed = new FullRecord
    processed.rowKey = rowKey3
    processed.uuid = uuid
    occurrenceDAO.updateOccurrence(rowKey3, processed, None, Versions.PROCESSED)
    val qa1 = QualityAssertion(AssertionCodes.TAXONOMIC_ISSUE, "", AssertionStatus.QA_UNCONFIRMED)
    qa1.userId = "Natasha.Carter@csiro.au"
    qa1.userDisplayName = "Natasha Carter"
    occurrenceDAO.addUserAssertion(rowKey3, qa1)
    expectResult(AssertionStatus.QA_UNCONFIRMED) {
      Integer.parseInt(pm.get(rowKey3, "occ", FullRecordMapper.userAssertionStatusColumn).get)
    }
    occurrenceDAO.deleteUserAssertion(rowKey3, qa1.uuid)
    expectResult(AssertionStatus.QA_NONE) {
      Integer.parseInt(pm.get(rowKey3, "occ", FullRecordMapper.userAssertionStatusColumn).get)
    }
  }

  test("Test add adhoc System assertion") {
    occurrenceDAO.addSystemAssertion("satest1", QualityAssertion(AssertionCodes.INFERRED_DUPLICATE_RECORD))
    occurrenceDAO.addSystemAssertion("satest1", QualityAssertion(AssertionCodes.INFERRED_DUPLICATE_RECORD, 1))

    expectResult(0) {
      val dups = occurrenceDAO.getSystemAssertions("satest1").filter(_.code == AssertionCodes.INFERRED_DUPLICATE_RECORD.code)

      dups(0).qaStatus
    }
    //now we want to remove the existing first
    occurrenceDAO.addSystemAssertion("satest1", QualityAssertion(AssertionCodes.INFERRED_DUPLICATE_RECORD, 1), replaceExistCode = true)
    expectResult(1) {
      val dups = occurrenceDAO.getSystemAssertions("satest1").filter(_.code == AssertionCodes.INFERRED_DUPLICATE_RECORD.code)

      dups(0).qaStatus
    }
  }
}