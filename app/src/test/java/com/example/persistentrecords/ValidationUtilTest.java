package com.example.persistentrecords;

import com.example.persistentrecords.model.ReadingRecord;
import com.example.persistentrecords.util.ValidationUtil;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.junit.Assert.*;

/**
 * JUnit tests for ValidationUtil - pure logic tests, no Android dependencies.
 */
@RunWith(JUnit4.class)
public class ValidationUtilTest {

    @Test
    public void validateSiteName_valid() {
        ValidationUtil.ValidationResult result = ValidationUtil.validateSiteName("Valid Site");
        assertTrue(result.isValid);
        assertNull(result.errorMessage);
    }

    @Test
    public void validateSiteName_empty() {
        ValidationUtil.ValidationResult result = ValidationUtil.validateSiteName("");
        assertFalse(result.isValid);
        assertEquals("Site name is required", result.errorMessage);
    }

    @Test
    public void validateSiteName_null() {
        ValidationUtil.ValidationResult result = ValidationUtil.validateSiteName(null);
        assertFalse(result.isValid);
        assertEquals("Site name is required", result.errorMessage);
    }

    @Test
    public void validateSiteName_tooShort() {
        ValidationUtil.ValidationResult result = ValidationUtil.validateSiteName("Ab");
        assertFalse(result.isValid);
        assertTrue(result.errorMessage.contains("at least 3"));
    }

    @Test
    public void validateSiteName_exactMin() {
        ValidationUtil.ValidationResult result = ValidationUtil.validateSiteName("Abc");
        assertTrue(result.isValid);
    }

    @Test
    public void validateSiteName_tooLong() {
        String longName = "A".repeat(41);
        ValidationUtil.ValidationResult result = ValidationUtil.validateSiteName(longName);
        assertFalse(result.isValid);
        assertTrue(result.errorMessage.contains("40"));
    }

    @Test
    public void validateSiteName_exactMax() {
        String maxName = "A".repeat(40);
        ValidationUtil.ValidationResult result = ValidationUtil.validateSiteName(maxName);
        assertTrue(result.isValid);
    }

    @Test
    public void validateSiteName_whitespace() {
        ValidationUtil.ValidationResult result = ValidationUtil.validateSiteName("  Site  ");
        assertTrue(result.isValid);
    }

    @Test
    public void validateReadingType_validTypes() {
        assertTrue(ValidationUtil.validateReadingType(ReadingRecord.TYPE_TEMPERATURE).isValid);
        assertTrue(ValidationUtil.validateReadingType(ReadingRecord.TYPE_HUMIDITY).isValid);
        assertTrue(ValidationUtil.validateReadingType(ReadingRecord.TYPE_SOIL_MOISTURE).isValid);
    }

    @Test
    public void validateReadingType_invalid() {
        ValidationUtil.ValidationResult result = ValidationUtil.validateReadingType("Invalid");
        assertFalse(result.isValid);
        assertEquals("Invalid reading type", result.errorMessage);
    }

    @Test
    public void validateReadingType_null() {
        ValidationUtil.ValidationResult result = ValidationUtil.validateReadingType(null);
        assertFalse(result.isValid);
        assertEquals("Reading type is required", result.errorMessage);
    }

    @Test
    public void validateValue_temperatureValid() {
        ValidationUtil.ValidationResult result = ValidationUtil.validateValue("25.5", ReadingRecord.TYPE_TEMPERATURE);
        assertTrue(result.isValid);
    }

    @Test
    public void validateValue_temperatureMin() {
        ValidationUtil.ValidationResult result = ValidationUtil.validateValue("-10", ReadingRecord.TYPE_TEMPERATURE);
        assertTrue(result.isValid);
    }

    @Test
    public void validateValue_temperatureMax() {
        ValidationUtil.ValidationResult result = ValidationUtil.validateValue("60", ReadingRecord.TYPE_TEMPERATURE);
        assertTrue(result.isValid);
    }

    @Test
    public void validateValue_temperatureBelowMin() {
        ValidationUtil.ValidationResult result = ValidationUtil.validateValue("-11", ReadingRecord.TYPE_TEMPERATURE);
        assertFalse(result.isValid);
        assertTrue(result.errorMessage.contains("-10") && result.errorMessage.contains("60"));
    }

    @Test
    public void validateValue_temperatureAboveMax() {
        ValidationUtil.ValidationResult result = ValidationUtil.validateValue("61", ReadingRecord.TYPE_TEMPERATURE);
        assertFalse(result.isValid);
    }

    @Test
    public void validateValue_humidityValid() {
        ValidationUtil.ValidationResult result = ValidationUtil.validateValue("50", ReadingRecord.TYPE_HUMIDITY);
        assertTrue(result.isValid);
    }

    @Test
    public void validateValue_humidityRange() {
        assertTrue(ValidationUtil.validateValue("0", ReadingRecord.TYPE_HUMIDITY).isValid);
        assertTrue(ValidationUtil.validateValue("100", ReadingRecord.TYPE_HUMIDITY).isValid);
        assertFalse(ValidationUtil.validateValue("-1", ReadingRecord.TYPE_HUMIDITY).isValid);
        assertFalse(ValidationUtil.validateValue("101", ReadingRecord.TYPE_HUMIDITY).isValid);
    }

    @Test
    public void validateValue_soilMoistureValid() {
        ValidationUtil.ValidationResult result = ValidationUtil.validateValue("75.5", ReadingRecord.TYPE_SOIL_MOISTURE);
        assertTrue(result.isValid);
    }

    @Test
    public void validateValue_soilMoistureRange() {
        assertTrue(ValidationUtil.validateValue("0", ReadingRecord.TYPE_SOIL_MOISTURE).isValid);
        assertTrue(ValidationUtil.validateValue("100", ReadingRecord.TYPE_SOIL_MOISTURE).isValid);
        assertFalse(ValidationUtil.validateValue("-1", ReadingRecord.TYPE_SOIL_MOISTURE).isValid);
        assertFalse(ValidationUtil.validateValue("101", ReadingRecord.TYPE_SOIL_MOISTURE).isValid);
    }

    @Test
    public void validateValue_nonNumeric() {
        ValidationUtil.ValidationResult result = ValidationUtil.validateValue("abc", ReadingRecord.TYPE_TEMPERATURE);
        assertFalse(result.isValid);
        assertEquals("Value must be a valid number", result.errorMessage);
    }

    @Test
    public void validateValue_empty() {
        ValidationUtil.ValidationResult result = ValidationUtil.validateValue("", ReadingRecord.TYPE_TEMPERATURE);
        assertFalse(result.isValid);
        assertEquals("Value is required", result.errorMessage);
    }

    @Test
    public void validateValue_null() {
        ValidationUtil.ValidationResult result = ValidationUtil.validateValue(null, ReadingRecord.TYPE_TEMPERATURE);
        assertFalse(result.isValid);
        assertEquals("Value is required", result.errorMessage);
    }

    @Test
    public void validateNotes_valid() {
        assertTrue(ValidationUtil.validateNotes("Short note").isValid);
        assertTrue(ValidationUtil.validateNotes("").isValid);
        assertTrue(ValidationUtil.validateNotes(null).isValid);
    }

    @Test
    public void validateNotes_exactMax() {
        String maxNotes = "A".repeat(120);
        assertTrue(ValidationUtil.validateNotes(maxNotes).isValid);
    }

    @Test
    public void validateNotes_tooLong() {
        String longNotes = "A".repeat(121);
        ValidationUtil.ValidationResult result = ValidationUtil.validateNotes(longNotes);
        assertFalse(result.isValid);
        assertTrue(result.errorMessage.contains("120"));
    }

    @Test
    public void validateForm_allValid() {
        ValidationUtil.FormValidationResult result = ValidationUtil.validateForm(
                "Valid Site", ReadingRecord.TYPE_TEMPERATURE, "25.0", "Notes");
        assertTrue(result.isAllValid());
    }

    @Test
    public void validateForm_invalidSiteName() {
        ValidationUtil.FormValidationResult result = ValidationUtil.validateForm(
                "Ab", ReadingRecord.TYPE_TEMPERATURE, "25.0", "Notes");
        assertFalse(result.isAllValid());
        assertFalse(result.siteName.isValid);
    }

    @Test
    public void validateForm_invalidValue() {
        ValidationUtil.FormValidationResult result = ValidationUtil.validateForm(
                "Valid Site", ReadingRecord.TYPE_TEMPERATURE, "100", "Notes");
        assertFalse(result.isAllValid());
        assertFalse(result.value.isValid);
    }
}