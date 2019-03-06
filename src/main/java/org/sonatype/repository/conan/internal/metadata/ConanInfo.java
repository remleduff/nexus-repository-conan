package org.sonatype.repository.conan.internal.metadata;

import com.fasterxml.jackson.annotation.JsonValue;
import org.slf4j.Logger;
import org.sonatype.goodies.common.Loggers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Supports converting from ini format to json to support conan search
 */
public class ConanInfo {

    private static final Logger LOGGER = Loggers.getLogger(ConanInfo.class);

    private final Map<String, Attribute> data = new HashMap<>();

    public static ConanInfo parse(InputStream inputStream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            return ConanInfoLoader.load(reader);
        } catch (IOException e) {
            LOGGER.warn("Unable to load conaninfo file");
        }
        return new ConanInfo();
    }

    public static ConanInfo load(BufferedReader reader) {
        return ConanInfoLoader.load(reader);
    }

    public Object getAttribute(String key) {
        return data.get(key).getValues();
    }

    @JsonValue
    public Map<String, Attribute> getData() {
        return data;
    }

    public interface Attribute {
        @JsonValue
        Object getValues();
    }

    private static class ConanInfoLoader {
        private static String key = null;
        private static InitAttribute currentAttribute = null;

        public static ConanInfo load(BufferedReader reader) {
            ConanInfo result = new ConanInfo();
            Pattern sectionPattern = Pattern.compile("\\[([^]]*)\\]");

            reader.lines().forEach(line -> {
                Matcher sectionMatcher = sectionPattern.matcher(line);

                // Start a new section?
                if (sectionMatcher.matches()) {
                    // Push the previous attribute
                    if (currentAttribute != null) {
                        result.data.put(key, currentAttribute);
                    }
                    key = sectionMatcher.group(1);
                    currentAttribute = null;
                } else {
                    // First line of new section, figure out the type of the section
                    if (currentAttribute == null) {
                        if (line.contains("=")) {
                            currentAttribute = new MapAttribute();
                        } else {
                            // If this is a single item it will be a string, if a second item arrives it becomes a list
                            currentAttribute = new StringAttribute();
                        }
                    }
                    currentAttribute.add(line);
                }
            });
            if (currentAttribute != null) {
                result.data.put(key, currentAttribute);
            }
            return result;
        }

        protected interface InitAttribute extends Attribute {
            void add(String value);
        }

        private static class MapAttribute implements Attribute, InitAttribute {
            private Map<String, String> data = new HashMap<>();

            @Override
            public Object getValues() {
                return data;
            }

            @Override
            public void add(String value) {
                String[] split = value.split("=");
                if (split.length == 2) {
                    data.put(split[0].trim(), split[1].trim());
                }
            }

            @Override
            public boolean equals(Object o) {
                return Objects.equals(data, o);
            }

            @Override
            public int hashCode() {
                return Objects.hash(data);
            }
        }

        private static class ListAttribute implements Attribute, InitAttribute {
            private List<String> data = new ArrayList<>();

            @Override
            public Object getValues() {
                return data;
            }

            @Override
            public void add(String value) {
                String v = value.trim();
                if (!v.isEmpty()) {
                    data.add(v);
                }
            }

            @Override
            public boolean equals(Object o) {
                return Objects.equals(data, o);
            }

            @Override
            public int hashCode() {
                return Objects.hash(data);
            }
        }

        private static class StringAttribute implements Attribute, InitAttribute {
            private String data;

            @Override
            public Object getValues() {
                return data;
            }

            @Override
            public void add(String value) {
                String v = value.trim();

                if (!v.isEmpty()) {
                    if (data == null) {
                        data = v;
                    } else {
                        // Convert this attribute from a string to a list
                        currentAttribute = new ListAttribute();
                        currentAttribute.add(data);
                        currentAttribute.add(v);
                    }
                }
            }

            @Override
            public boolean equals(Object o) {
                return Objects.equals(data, o);
            }

            @Override
            public int hashCode() {
                return Objects.hash(data);
            }
        }
    }
}
