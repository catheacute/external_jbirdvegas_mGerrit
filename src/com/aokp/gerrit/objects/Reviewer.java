package com.aokp.gerrit.objects;

/**
 * Created with IntelliJ IDEA.
 * User: jbird
 * Date: 4/3/13 6:53 PM
 */
public class Reviewer {
    public static final String NO_SCORE = "No score";
    public static final String CODE_REVIEW_PLUS_TWO = "Looks good to me, approved";
    public static final String CODE_REVIEW_PLUS_ONE = "Looks good to me, but someone else must approve";
    public static final String CODE_REVIEW_MINUS_ONE = "I would prefer that you didn\u0027t submit this";
    public static final String CODE_REVIEW_MINUS_TWO = "Do not submit";
    public static final String VERIFIED_PLUS_ONE = "Verified";
    public static final String VERIFIED_MINUS_ONE = "Fails";
    public Reviewer(String val, String _name) {
        value = val;
        name = _name;
    }

    private String value;
    private String name;

    public String getValue() {
        return value;
    }

    public String getName() {
        return name;
    }
}
