package org.mydotey.scf.type;

/**
 * @author koqizhao
 *
 * May 21, 2018
 */
public class StringToBooleanConverter extends StringConverter<Boolean> {

    public static StringToBooleanConverter DEFAULT = new StringToBooleanConverter();

    public StringToBooleanConverter() {
        super(Boolean.class);
    }

    @Override
    public Boolean convert(String source) {
        return Boolean.parseBoolean(source);
    }

}