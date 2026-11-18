package com.example.persistentrecords.util;

import com.example.persistentrecords.model.ReadingRecord;

/**
 * Validation utilities for the Field Readings form.
 * All validation rules centralized here for testability.
 */
public class ValidationUtil {

    public static final int SITE_NAME_MIN_LENGTH = 3;
    public static final int SITE_NAME_MAX_LENGTH = 40;
    public static final int NOTES_MAX_LENGTH = 120;

    public static class ValidationResult {
        public final boolean isValid;
        public final String errorMessage;

        public ValidationResult(boolean isValid, String errorMessage) {
            this.isValid = isValid;
            this.errorMessage = errorMessage;
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message);
        }
    }

    /**
     * Validates site name: required, 3-40 characters.
     */
    public static ValidationResult validateSiteName(String siteName) {
        if (siteName == null || siteName.trim().isEmpty()) {
            return ValidationResult.invalid("Site name is required");
        }
        String trimmed = siteName.trim();
        if (trimmed.length() < SITE_NAME_MIN_LENGTH) {
            return ValidationResult.invalid("Site name must be at least " + SITE_NAME_MIN_LENGTH + " characters");
        }
        if (trimmed.length() > SITE_NAME_MAX_LENGTH) {
            return ValidationResult.invalid("Site name must not exceed " + SITE_NAME_MAX_LENGTH + " characters");
        }
        return ValidationResult.valid();
    }

    /**
     * Validates reading type: must be one of the allowed types.
     */
    public static ValidationResult validateReadingType(String readingType) {
        if (readingType == null || readingType.isEmpty()) {
            return ValidationResult.invalid("Reading type is required");
        }
        if (!ReadingRecord.TYPE_TEMPERATURE.equals(readingType)
                && !ReadingRecord.TYPE_HUMIDITY.equals(readingType)
                && !ReadingRecord.TYPE_SOIL_MOISTURE.equals(readingType)) {
            return ValidationResult.invalid("Invalid reading type");
        }
        return ValidationResult.valid();
    }

    /**
     * Validates value: required, numeric, within range for the type.
     */
    public static ValidationResult validateValue(String valueStr, String readingType) {
        if (valueStr == null || valueStr.trim().isEmpty()) {
            return ValidationResult.invalid("Value is required");
        }

        double value;
        try {
            value = Double.parseDouble(valueStr.trim());
        } catch (NumberFormatException e) {
            return ValidationResult.invalid("Value must be a valid number");
        }

        int[] range = ReadingRecord.getRangeForType(readingType);
        if (range == null) {
            return ValidationResult.invalid("Unknown reading type");
        }

        if (value < range[0] || value > range[1]) {
            return ValidationResult.invalid(
                    String.format("Value must be between %d and %d for %s", range[0], range[1], readingType));
        }

        return ValidationResult.valid();
    }

    /**
     * Validates notes: optional, max 120 characters.
     */
    public static ValidationResult validateNotes(String notes) {
        if (notes != null && notes.length() > NOTES_MAX_LENGTH) {
            return ValidationResult.invalid("Notes must not exceed " + NOTES_MAX_LENGTH + " characters");
        }
        return ValidationResult.valid();
    }

    /**
     * Validates all fields at once.
     */
    public static class FormValidationResult {
        public final ValidationResult siteName;
        public final ValidationResult readingType;
        public final ValidationResult value;
        public final ValidationResult notes;

        public FormValidationResult(ValidationResult siteName, ValidationResult readingType,
                                    ValidationResult value, ValidationResult notes) {
            this.siteName = siteName;
            this.readingType = readingType;
            this.value = value;
            this.notes = notes;
        }

        public boolean isAllValid() {
            return siteName.isValid && readingType.isValid && value.isValid && notes.isValid;
        }
    }

    public static FormValidationResult validateForm(String siteName, String readingType,
                                                     String value, String notes) {
        return new FormValidationResult(
                validateSiteName(siteName),
                validateReadingType(readingType),
                validateValue(value, readingType),
                validateNotes(notes)
        );
    }
}