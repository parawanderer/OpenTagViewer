package dev.wander.android.airtagforall.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

public class YamlParser {
    public static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());
}
