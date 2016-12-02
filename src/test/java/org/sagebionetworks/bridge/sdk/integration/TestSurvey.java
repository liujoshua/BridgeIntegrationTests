package org.sagebionetworks.bridge.sdk.integration;

import java.math.BigDecimal;
import java.util.List;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.LocalDate;

import org.sagebionetworks.bridge.rest.model.BooleanConstraints;
import org.sagebionetworks.bridge.rest.model.DataType;
import org.sagebionetworks.bridge.rest.model.DateConstraints;
import org.sagebionetworks.bridge.rest.model.DateTimeConstraints;
import org.sagebionetworks.bridge.rest.model.DecimalConstraints;
import org.sagebionetworks.bridge.rest.model.DurationConstraints;
import org.sagebionetworks.bridge.rest.model.Image;
import org.sagebionetworks.bridge.rest.model.IntegerConstraints;
import org.sagebionetworks.bridge.rest.model.MultiValueConstraints;
import org.sagebionetworks.bridge.rest.model.Operator;
import org.sagebionetworks.bridge.rest.model.StringConstraints;
import org.sagebionetworks.bridge.rest.model.Survey;
import org.sagebionetworks.bridge.rest.model.SurveyElement;
import org.sagebionetworks.bridge.rest.model.SurveyQuestion;
import org.sagebionetworks.bridge.rest.model.SurveyQuestionOption;
import org.sagebionetworks.bridge.rest.model.SurveyRule;
import org.sagebionetworks.bridge.rest.model.TimeConstraints;
import org.sagebionetworks.bridge.rest.model.UIHint;
import org.sagebionetworks.bridge.rest.model.Unit;

import com.google.common.collect.Lists;

public class TestSurvey {
    
    public static final String MULTIVALUE_ID = "feeling";
    public static final String STRING_ID = "phone_number";
    public static final String BOOLEAN_ID = "high_bp";
    public static final String DATE_ID = "last_checkup";
    public static final String DATETIME_ID = "last_reading";
    public static final String DECIMAL_ID = "deleuterium_dosage";
    public static final String DURATION_ID = "time_for_appt";
    public static final String INTEGER_ID = "BP X DAY";
    public static final String TIME_ID = "deleuterium_x_day";
    
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
    
    public static Survey getSurvey(Class<?> cls) {
        Survey survey = new Survey();
        
        SurveyQuestion multiValueQuestion = new SurveyQuestion();
        Image terrible = image("http://terrible.svg", 600, 300);
        Image poor = image("http://poor.svg", 600, 300);
        Image ok = image("http://ok.svg", 600, 300);
        Image good = image("http://good.svg", 600, 300);
        Image great = image("http://great.svg", 600, 300);
        MultiValueConstraints mvc = new MultiValueConstraints();
        List<SurveyQuestionOption> options = Lists.newArrayList(
                option("Terrible", null, "1", terrible), 
                option("Poor", null, "2", poor),
                option("OK", null, "3", ok), 
                option("Good", null, "4", good),
                option("Great", null, "5", great));
        mvc.setEnumeration(options);
        mvc.setAllowOther(false);
        mvc.setAllowMultiple(true);
        mvc.setDataType(DataType.STRING);
        multiValueQuestion.setConstraints(mvc);
        multiValueQuestion.setPrompt("How do you feel today?");
        multiValueQuestion.setIdentifier(MULTIVALUE_ID);
        multiValueQuestion.setUiHint(UIHint.LIST);
        multiValueQuestion.setType("SurveyQuestion");
        
        SurveyQuestion stringQuestion = new SurveyQuestion();
        StringConstraints c = new StringConstraints();
        c.setMinLength(2);
        c.setMaxLength(255);
        c.setPattern("\\d{3}-\\d{3}-\\d{4}");
        c.setDataType(DataType.STRING);
        stringQuestion.setPrompt("Please enter an emergency phone number (###-###-####)?");
        stringQuestion.setPromptDetail("This should be someone else's number.");
        stringQuestion.setIdentifier(STRING_ID);
        stringQuestion.setConstraints(c);
        stringQuestion.setUiHint(UIHint.TEXTFIELD);
        stringQuestion.setType("SurveyQuestion");
        
        SurveyQuestion booleanQuestion = new SurveyQuestion();
        BooleanConstraints c9 = new BooleanConstraints();
        c9.setDataType(DataType.BOOLEAN);
        booleanQuestion.setPrompt("Do you have high blood pressure?");
        booleanQuestion.setIdentifier(BOOLEAN_ID);
        booleanQuestion.setConstraints(c9);
        booleanQuestion.setUiHint(UIHint.CHECKBOX);
        booleanQuestion.setType("SurveyQuestion");
        
        SurveyQuestion dateQuestion = new SurveyQuestion();
        DateConstraints c2 = new DateConstraints();
        c2.setDataType(DataType.DATE);
        c2.setEarliestValue(LocalDate.parse("2000-01-01"));
        c2.setLatestValue(LocalDate.parse("2020-12-31"));
        c2.setAllowFuture(true);
        dateQuestion.setPrompt("When did you last have a medical check-up?");
        dateQuestion.setIdentifier(DATE_ID);
        dateQuestion.setConstraints(c2);
        dateQuestion.setUiHint(UIHint.DATEPICKER);
        dateQuestion.setType("SurveyQuestion");
        
        SurveyQuestion dateTimeQuestion = new SurveyQuestion();
        DateTimeConstraints c3 = new DateTimeConstraints();
        c3.setAllowFuture(true);
        c3.setEarliestValue(DateTime.parse("2000-01-01").withZone(DateTimeZone.UTC));
        c3.setLatestValue(DateTime.parse("2020-12-31").withZone(DateTimeZone.UTC));
        c3.setDataType(DataType.DATETIME);
        dateTimeQuestion.setPrompt("When is your next medical check-up scheduled?");
        dateTimeQuestion.setIdentifier(DATETIME_ID);
        dateTimeQuestion.setConstraints(c3);
        dateTimeQuestion.setUiHint(UIHint.DATETIMEPICKER);
        dateTimeQuestion.setType("SurveyQuestion");
        
        SurveyQuestion decimalQuestion = new SurveyQuestion();
        DecimalConstraints c4 = new DecimalConstraints();
        c4.setMinValue(BigDecimal.valueOf(0.0d));
        c4.setMaxValue(BigDecimal.valueOf(10.0d));
        c4.setStep(BigDecimal.valueOf(0.1d));
        c4.setDataType(DataType.DECIMAL);
        decimalQuestion.setPrompt("What dosage (in grams) do you take of deleuterium each day?");
        decimalQuestion.setIdentifier(DECIMAL_ID);
        decimalQuestion.setConstraints(c4);
        decimalQuestion.setUiHint(UIHint.NUMBERFIELD);
        decimalQuestion.setType("SurveyQuestion");
        
        SurveyQuestion durationQuestion = new SurveyQuestion();
        DurationConstraints c5 = new DurationConstraints();
        c5.setMinValue(1);
        c5.setMaxValue(120);
        c5.setUnit(Unit.MINUTES);
        c5.setDataType(DataType.DURATION);
        durationQuestion.setPrompt("How log does your appointment take, on average?");
        durationQuestion.setIdentifier(DURATION_ID);
        durationQuestion.setConstraints(c5);
        durationQuestion.setUiHint(UIHint.SLIDER);
        durationQuestion.setType("SurveyQuestion");
        
        SurveyRule rule1 = rule(Operator.LE, "2", "phone_number");
        SurveyRule rule2 = rule(Operator.DE, null, "phone_number");
        
        SurveyQuestion integerQuestion = new SurveyQuestion();
        IntegerConstraints c6 = new IntegerConstraints();
        c6.setMinValue(0);
        c6.setMaxValue(8);
        c6.setRules(Lists.newArrayList(rule1, rule2));
        c6.setDataType(DataType.INTEGER);
        integerQuestion.setPrompt("How many times a day do you take your blood pressure?");
        integerQuestion.setIdentifier(INTEGER_ID);
        integerQuestion.setConstraints(c6);
        integerQuestion.setUiHint(UIHint.NUMBERFIELD);
        integerQuestion.setType("SurveyQuestion");
        
        SurveyQuestion timeQuestion = new SurveyQuestion();
        TimeConstraints c7 = new TimeConstraints();
        c7.setDataType(DataType.TIME);
        timeQuestion.setPrompt("What times of the day do you take deleuterium?");
        timeQuestion.setIdentifier(TIME_ID);
        timeQuestion.setConstraints(c7);
        timeQuestion.setUiHint(UIHint.TIMEPICKER);
        timeQuestion.setType("SurveyQuestion");
        
        survey.setName("General Blood Pressure Survey");
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

        return survey;
    }

}
