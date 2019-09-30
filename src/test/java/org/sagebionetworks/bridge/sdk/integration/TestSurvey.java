package org.sagebionetworks.bridge.sdk.integration;

import java.math.BigDecimal;
import java.util.List;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.LocalDate;

import org.sagebionetworks.bridge.rest.model.BloodPressureConstraints;
import org.sagebionetworks.bridge.rest.model.BooleanConstraints;
import org.sagebionetworks.bridge.rest.model.CountryCode;
import org.sagebionetworks.bridge.rest.model.DataType;
import org.sagebionetworks.bridge.rest.model.DateConstraints;
import org.sagebionetworks.bridge.rest.model.DateTimeConstraints;
import org.sagebionetworks.bridge.rest.model.DecimalConstraints;
import org.sagebionetworks.bridge.rest.model.DurationConstraints;
import org.sagebionetworks.bridge.rest.model.HeightConstraints;
import org.sagebionetworks.bridge.rest.model.Image;
import org.sagebionetworks.bridge.rest.model.IntegerConstraints;
import org.sagebionetworks.bridge.rest.model.MultiValueConstraints;
import org.sagebionetworks.bridge.rest.model.Operator;
import org.sagebionetworks.bridge.rest.model.PostalCodeConstraints;
import org.sagebionetworks.bridge.rest.model.StringConstraints;
import org.sagebionetworks.bridge.rest.model.Survey;
import org.sagebionetworks.bridge.rest.model.SurveyElement;
import org.sagebionetworks.bridge.rest.model.SurveyQuestion;
import org.sagebionetworks.bridge.rest.model.SurveyQuestionOption;
import org.sagebionetworks.bridge.rest.model.SurveyRule;
import org.sagebionetworks.bridge.rest.model.TimeConstraints;
import org.sagebionetworks.bridge.rest.model.UIHint;
import org.sagebionetworks.bridge.rest.model.Unit;
import org.sagebionetworks.bridge.rest.model.WeightConstraints;
import org.sagebionetworks.bridge.rest.model.YearConstraints;
import org.sagebionetworks.bridge.rest.model.YearMonthConstraints;

import com.google.common.collect.Lists;

public class TestSurvey {
    public static final DateTimeZone MST = DateTimeZone.forOffsetHours(3); 
    public static final String YEARMONTH_QUESTION_LATEST_VALUE = "2020-12";
    public static final String YEARMONTH_QUESTION_EARLIEST_VALUE = "2018-01";
    public static final String STRING_QUESTION_PLACEHOLDER = "###-###-####";
    public static final String STRING_QUESTION_ERROR_MESSAGE = "Please enter phone number";
    public static final String STRING_QUESTION_PATTERN = "\\d{3}-\\d{3}-\\d{4}";
    public static final Integer STRING_QUESTION_MAX_LENGTH = 255;
    public static final Integer STRING_QUESTION_MIN_LENGTH = 2;
    public static final Unit DURATION_QUESTION_UNIT = Unit.MINUTES;
    public static final Integer DURATION_QUESTION_STEP = 5;
    public static final Integer DURATION_QUESTION_MAX_VALUE = 120;
    public static final Integer DURATION_QUESTION_MIN_VALUE = 1;
    public static final Integer INT_QUESTION_MAX_VALUE = 8;
    public static final Integer INT_QUESTION_MIN_VALUE = 0;
    public static final Integer INT_QUESTION_STEP = 1;
    public static final Unit INT_QUESTION_UNIT = Unit.DAYS;
    public static final BigDecimal DECIMAL_QUESTION_STEP = BigDecimal.valueOf(0.1d);
    public static final BigDecimal DECIMAL_QUESTION_MAX_VALUE = BigDecimal.valueOf(10.0d);
    public static final BigDecimal DECIMAL_QUESTION_MIN_VALUE = BigDecimal.valueOf(0.0d);
    public static final DateTime DATETIME_LATEST_VALUE = DateTime.parse("2020-12-31").withZone(MST);
    public static final DateTime DATETIME_EARLIEST_VALUE = DateTime.parse("2000-01-01").withZone(MST);
    public static final LocalDate DATE_QUESTION_LATEST_VALUE = LocalDate.parse("2020-12-31");
    public static final LocalDate DATE_QUESTION_EARLIEST_VALUE = LocalDate.parse("2000-01-01");
    public static final String COPYRIGHT_NOTICE = "Copyright notice";
    public static final String MODULE_ID = "test-survey-module";
    public static final int MODULE_VERSION = 3;
    public static final String MULTIVALUE_ID = "feeling";
    public static final String STRING_ID = "phone_number";
    public static final String BOOLEAN_ID = "high_bp";
    public static final String DATE_ID = "last_checkup";
    public static final String DATETIME_ID = "last_reading";
    public static final String DECIMAL_ID = "deleuterium_dosage";
    public static final String DURATION_ID = "time_for_appt";
    public static final String INTEGER_ID = "BP X DAY";
    public static final String TIME_ID = "deleuterium_x_day";
    public static final String BLOODPRESSURE_ID = "bloodpressure";
    public static final String HEIGHT_ID = "height";
    public static final String WEIGHT_ID = "weight";
    public static final String YEARMONTH_ID = "yearmonth";
    public static final String POSTALCODE_ID = "postalcode";
    public static final String YEAR_ID = "year";

    private static Image image(String url, int width, int height) {
        Image image = new Image();
        image.setSource(url);
        image.setWidth(width);
        image.setHeight(height);
        return image;
    }
    
    private static SurveyQuestionOption option(String label, String detail, String value, Image image) {
        SurveyQuestionOption option = new SurveyQuestionOption();
        option.setLabel(label);
        option.setDetail(detail);
        option.setValue(value);
        option.setImage(image);
        return option;
    }
    
    private static SurveyRule rule(Operator op, String value, String skipToTarget) {
        SurveyRule rule = new SurveyRule();
        rule.setOperator(op);
        rule.setValue(value);
        rule.setSkipTo(skipToTarget);
        return rule;
    }
    
    public static Survey getSurvey(Class<?> cls) throws Exception {
        Survey survey = new Survey();
        
        SurveyQuestion multiValueQuestion = new SurveyQuestion();
        Image terrible = image("http://terrible.svg", 600, 300);
        Image poor = image("http://poor.svg", 600, 300);
        Image ok = image("http://ok.svg", 600, 300);
        Image good = image("http://good.svg", 600, 300);
        Image great = image("http://great.svg", 600, 300);
        MultiValueConstraints mvc = new MultiValueConstraints();
        List<SurveyQuestionOption> options = Lists.newArrayList(
                option("Terrible", "Terrible Detail", "1", terrible), 
                option("Poor", "Poor Detail", "2", poor),
                option("OK", "OK Detail", "3", ok), 
                option("Good", "Good Detail", "4", good),
                option("Great", "Great Detail", "5", great));
        mvc.setEnumeration(options);
        mvc.setAllowOther(false);
        mvc.setAllowMultiple(true);
        mvc.setDataType(DataType.STRING);
        multiValueQuestion.setConstraints(mvc);
        multiValueQuestion.setPrompt("How do you feel today?");
        multiValueQuestion.setIdentifier(MULTIVALUE_ID);
        multiValueQuestion.setUiHint(UIHint.LIST);
        Tests.setVariableValueInObject(multiValueQuestion, "type", "SurveyQuestion");

        SurveyQuestion stringQuestion = new SurveyQuestion();
        StringConstraints c = new StringConstraints();
        c.setMinLength(STRING_QUESTION_MIN_LENGTH);
        c.setMaxLength(STRING_QUESTION_MAX_LENGTH);
        c.setPattern(STRING_QUESTION_PATTERN);
        c.setPatternErrorMessage(STRING_QUESTION_ERROR_MESSAGE);
        c.setPatternPlaceholder(STRING_QUESTION_PLACEHOLDER);
        c.setDataType(DataType.STRING);
        stringQuestion.setPrompt("Please enter an emergency phone number (###-###-####)?");
        stringQuestion.setPromptDetail("This should be someone else's number.");
        stringQuestion.setIdentifier(STRING_ID);
        stringQuestion.setConstraints(c);
        stringQuestion.setUiHint(UIHint.TEXTFIELD);
        Tests.setVariableValueInObject(stringQuestion, "type", "SurveyQuestion");
        
        SurveyQuestion booleanQuestion = new SurveyQuestion();
        BooleanConstraints c1 = new BooleanConstraints();
        c1.setDataType(DataType.BOOLEAN);
        booleanQuestion.setPrompt("Do you have high blood pressure?");
        booleanQuestion.setIdentifier(BOOLEAN_ID);
        booleanQuestion.setConstraints(c1);
        booleanQuestion.setUiHint(UIHint.CHECKBOX);
        Tests.setVariableValueInObject(booleanQuestion, "type", "SurveyQuestion");
        
        SurveyQuestion dateQuestion = new SurveyQuestion();
        DateConstraints c2 = new DateConstraints();
        c2.setDataType(DataType.DATE);
        c2.setEarliestValue(DATE_QUESTION_EARLIEST_VALUE);
        c2.setLatestValue(DATE_QUESTION_LATEST_VALUE);
        c2.setAllowPast(false);
        c2.setAllowFuture(true);
        dateQuestion.setPrompt("When did you last have a medical check-up?");
        dateQuestion.setIdentifier(DATE_ID);
        dateQuestion.setConstraints(c2);
        dateQuestion.setUiHint(UIHint.DATEPICKER);
        Tests.setVariableValueInObject(dateQuestion, "type", "SurveyQuestion");
        
        SurveyQuestion dateTimeQuestion = new SurveyQuestion();
        DateTimeConstraints c3 = new DateTimeConstraints();
        c3.setAllowPast(false);
        c3.setAllowFuture(true);
        c3.setEarliestValue(DATETIME_EARLIEST_VALUE);
        c3.setLatestValue(DATETIME_LATEST_VALUE);
        c3.setDataType(DataType.DATETIME);
        dateTimeQuestion.setPrompt("When is your next medical check-up scheduled?");
        dateTimeQuestion.setIdentifier(DATETIME_ID);
        dateTimeQuestion.setConstraints(c3);
        dateTimeQuestion.setUiHint(UIHint.DATETIMEPICKER);
        Tests.setVariableValueInObject(dateTimeQuestion, "type", "SurveyQuestion");

        SurveyQuestion decimalQuestion = new SurveyQuestion();
        DecimalConstraints c4 = new DecimalConstraints();
        c4.setMinValue(DECIMAL_QUESTION_MIN_VALUE);
        c4.setMaxValue(DECIMAL_QUESTION_MAX_VALUE);
        c4.setStep(DECIMAL_QUESTION_STEP);
        c4.setUnit(Unit.GRAMS);
        c4.setDataType(DataType.DECIMAL);
        decimalQuestion.setPrompt("What dosage (in grams) do you take of deleuterium each day?");
        decimalQuestion.setIdentifier(DECIMAL_ID);
        decimalQuestion.setConstraints(c4);
        decimalQuestion.setUiHint(UIHint.NUMBERFIELD);
        Tests.setVariableValueInObject(decimalQuestion, "type", "SurveyQuestion");

        SurveyQuestion durationQuestion = new SurveyQuestion();
        DurationConstraints c5 = new DurationConstraints();
        c5.setMinValue(DURATION_QUESTION_MIN_VALUE);
        c5.setMaxValue(DURATION_QUESTION_MAX_VALUE);
        c5.setStep(DURATION_QUESTION_STEP);
        c5.setUnit(DURATION_QUESTION_UNIT);
        c5.setDataType(DataType.DURATION);
        durationQuestion.setPrompt("How log does your appointment take, on average?");
        durationQuestion.setIdentifier(DURATION_ID);
        durationQuestion.setConstraints(c5);
        durationQuestion.setUiHint(UIHint.SLIDER);
        Tests.setVariableValueInObject(durationQuestion, "type", "SurveyQuestion");
        
        SurveyRule rule1 = rule(Operator.LE, "2", "phone_number");
        SurveyRule rule2 = rule(Operator.DE, null, "phone_number");
        
        SurveyQuestion integerQuestion = new SurveyQuestion();
        IntegerConstraints c6 = new IntegerConstraints();
        c6.setMinValue(INT_QUESTION_MIN_VALUE);
        c6.setMaxValue(INT_QUESTION_MAX_VALUE);
        c6.setStep(INT_QUESTION_STEP);
        c6.setUnit(INT_QUESTION_UNIT);
        c6.setDataType(DataType.INTEGER);
        integerQuestion.setPrompt("How many times a day do you take your blood pressure?");
        integerQuestion.setIdentifier(INTEGER_ID);
        integerQuestion.setConstraints(c6);
        integerQuestion.setUiHint(UIHint.NUMBERFIELD);
        integerQuestion.setAfterRules(Lists.newArrayList(rule1, rule2));
        Tests.setVariableValueInObject(integerQuestion, "type", "SurveyQuestion");
        
        SurveyQuestion timeQuestion = new SurveyQuestion();
        TimeConstraints c7 = new TimeConstraints();
        c7.setDataType(DataType.TIME);
        timeQuestion.setPrompt("What times of the day do you take deleuterium?");
        timeQuestion.setIdentifier(TIME_ID);
        timeQuestion.setConstraints(c7);
        timeQuestion.setUiHint(UIHint.TIMEPICKER);
        Tests.setVariableValueInObject(timeQuestion, "type", "SurveyQuestion");

        SurveyQuestion bloodpressureQuestion = new SurveyQuestion();
        BloodPressureConstraints c8 = new BloodPressureConstraints();
        c8.setDataType(DataType.BLOODPRESSURE);
        c8.setUnit(Unit.CUBIC_CENTIMETERS);
        bloodpressureQuestion.setConstraints(c8);
        bloodpressureQuestion.setPrompt("What is your blood pressure?");
        bloodpressureQuestion.setIdentifier(BLOODPRESSURE_ID);
        bloodpressureQuestion.setUiHint(UIHint.BLOODPRESSURE);
        Tests.setVariableValueInObject(bloodpressureQuestion, "type", "SurveyQuestion");

        SurveyQuestion heightQuestion = new SurveyQuestion();
        HeightConstraints c9 = new HeightConstraints();
        c9.setDataType(DataType.HEIGHT);
        c9.setUnit(Unit.CENTIMETERS);
        c9.setForInfant(true);
        heightQuestion.setConstraints(c9);
        heightQuestion.setPrompt("What is your height?");
        heightQuestion.setIdentifier(HEIGHT_ID);
        heightQuestion.setUiHint(UIHint.HEIGHT);
        Tests.setVariableValueInObject(heightQuestion, "type", "SurveyQuestion");

        SurveyQuestion weightQuestion = new SurveyQuestion();
        WeightConstraints c10 = new WeightConstraints();
        c10.setDataType(DataType.WEIGHT);
        c10.setUnit(Unit.KILOGRAMS);
        c10.setForInfant(true);
        weightQuestion.setConstraints(c10);
        weightQuestion.setPrompt("What is your weight?");
        weightQuestion.setIdentifier(WEIGHT_ID);
        weightQuestion.setUiHint(UIHint.WEIGHT);
        Tests.setVariableValueInObject(weightQuestion, "type", "SurveyQuestion");
        
        SurveyQuestion yearMonthQuestion = new SurveyQuestion();
        YearMonthConstraints c11 = new YearMonthConstraints();
        c11.setDataType(DataType.YEARMONTH);
        c11.setAllowPast(false);
        c11.setAllowFuture(true);
        c11.setEarliestValue(YEARMONTH_QUESTION_EARLIEST_VALUE);
        c11.setLatestValue(YEARMONTH_QUESTION_LATEST_VALUE);
        yearMonthQuestion.setConstraints(c11);
        yearMonthQuestion.setPrompt("What year and month?");
        yearMonthQuestion.setIdentifier(YEARMONTH_ID);
        yearMonthQuestion.setUiHint(UIHint.YEARMONTH);
        Tests.setVariableValueInObject(yearMonthQuestion, "type", "SurveyQuestion");
        
        SurveyQuestion postalCodeQuestion = new SurveyQuestion();
        PostalCodeConstraints pcc = new PostalCodeConstraints();
        pcc.setCountryCode(CountryCode.US);
        pcc.setDataType(DataType.POSTALCODE);
        postalCodeQuestion.setConstraints(pcc);
        postalCodeQuestion.setPrompt("Postal code?");
        postalCodeQuestion.setIdentifier(POSTALCODE_ID);
        postalCodeQuestion.setUiHint(UIHint.POSTALCODE);
        Tests.setVariableValueInObject(postalCodeQuestion, "type", "SurveyQuestion");
        
        SurveyQuestion yearQuestion = new SurveyQuestion();
        YearConstraints yc = new YearConstraints();
        yc.setAllowFuture(true);
        yc.setAllowPast(false);
        yc.setEarliestValue("2000");
        yc.setLatestValue("2020");
        yc.setDataType(DataType.YEAR);
        yearQuestion.setConstraints(yc);
        yearQuestion.setPrompt("When is your next MRI?");
        yearQuestion.setIdentifier(YEAR_ID);
        yearQuestion.setUiHint(UIHint.YEAR);
        Tests.setVariableValueInObject(yearQuestion, "type", "SurveyQuestion");

        survey.setName(cls.getSimpleName() + " Survey");
        survey.setIdentifier(Tests.randomIdentifier(cls));
        List<SurveyElement> elements = survey.getElements();
        elements.add(booleanQuestion);
        elements.add(dateQuestion);
        elements.add(dateTimeQuestion);
        elements.add(decimalQuestion);
        elements.add(integerQuestion);
        elements.add(durationQuestion);
        elements.add(timeQuestion);
        elements.add(multiValueQuestion);
        elements.add(stringQuestion);
        elements.add(bloodpressureQuestion);
        elements.add(heightQuestion);
        elements.add(weightQuestion);
        elements.add(yearMonthQuestion);
        elements.add(postalCodeQuestion);
        elements.add(yearQuestion);

        survey.setCopyrightNotice(COPYRIGHT_NOTICE);

        // Set optional parameters.
        survey.setModuleId(MODULE_ID);
        survey.setModuleVersion(MODULE_VERSION);

        return survey;
    }

}
