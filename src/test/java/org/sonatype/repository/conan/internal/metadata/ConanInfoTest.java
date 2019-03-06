package org.sonatype.repository.conan.internal.metadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockito.Mock;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.storage.TempBlob;

import static junit.framework.TestCase.assertTrue;
import static org.powermock.api.mockito.PowerMockito.when;

public class ConanInfoTest
        extends TestSupport {

    @Mock
    TempBlob blob;

    @Test
    public void canParse() throws JsonProcessingException {
        when(blob.get()).thenAnswer(stream -> getClass().getResourceAsStream("conaninfo.txt"));

        ConanInfo info = ConanInfo.parse(blob.get());

        assertTrue(info.getAttribute("recipe_hash").equals("3d7e81eae7738a9357d85e31372bce01"));

        ObjectMapper mapper = new ObjectMapper();

        String json = mapper.writeValueAsString(info);
        assertTrue(json.equals("{\"full_options\":{\"debug_postfix\":\"d\",\"shared\":\"False\",\"no_main\":\"False\",\"build_gmock\":\"True\",\"fPIC\":\"True\"},\"settings\":{\"os\":\"Linux\",\"compiler.libcxx\":\"libstdc++11\",\"arch\":\"x86_64\",\"compiler\":\"gcc\",\"build_type\":\"Debug\",\"compiler.version\":\"7\"},\"full_settings\":{\"os\":\"Linux\",\"compiler.libcxx\":\"libstdc++11\",\"arch\":\"x86_64\",\"compiler\":\"gcc\",\"build_type\":\"Debug\",\"compiler.version\":\"7\"},\"options\":{\"debug_postfix\":\"d\",\"shared\":\"False\",\"no_main\":\"False\",\"build_gmock\":\"True\",\"fPIC\":\"True\"},\"full_requires\":null,\"env\":null,\"recipe_hash\":\"3d7e81eae7738a9357d85e31372bce01\",\"requires\":null}"));
    }

}
