/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package com_google_protobuf.protobuf_java_util;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Protobuf_java_utilTest {
    @Test
    void testJsonFormat() throws Exception {
        // Create a sample Struct message
        Struct.Builder structBuilder = Struct.newBuilder();
        structBuilder.putFields("key", Value.newBuilder().setStringValue("value").build());
        Struct struct = structBuilder.build();

        // Use JsonFormat to print the message as JSON
        String json = JsonFormat.printer().print(struct);

        // Verify the JSON output
        assertThat(json).contains("\"key\":");

        // Use JsonFormat to parse the JSON back into a message
        Struct.Builder parsedStructBuilder = Struct.newBuilder();
        JsonFormat.parser().merge(json, parsedStructBuilder);
        Struct parsedStruct = parsedStructBuilder.build();

        // Verify the parsed message
        assertThat(parsedStruct.getFieldsMap()).containsKey("key");
    }

    @Test
    void testJsonFormatTypeRegistry() throws Exception {
        // Create a sample Any message with a type URL
        com.google.protobuf.Duration duration = com.google.protobuf.Duration.newBuilder()
                .setSeconds(1)
                .build();
        com.google.protobuf.Any any = com.google.protobuf.Any.newBuilder()
                .setTypeUrl("type.googleapis.com/google.protobuf.Duration")
                .setValue(duration.toByteString())
                .build();

        // Use JsonFormat with a TypeRegistry to print the Any message as JSON
        String json = JsonFormat.printer().usingTypeRegistry(JsonFormat.TypeRegistry.newBuilder()
                .add(com.google.protobuf.Duration.getDescriptor())
                .build())
                .print(any);

        // Verify the JSON output
        assertThat(json).contains("\"value\"");
        assertThat(json).contains("s");

        // Use JsonFormat to parse the JSON back into a message
        com.google.protobuf.Any.Builder parsedAnyBuilder = com.google.protobuf.Any.newBuilder();
        JsonFormat.parser().usingTypeRegistry(JsonFormat.TypeRegistry.newBuilder()
                .add(com.google.protobuf.Duration.getDescriptor())
                .build())
                .merge(json, parsedAnyBuilder);
        com.google.protobuf.Any parsedAny = parsedAnyBuilder.build();

        // Verify the parsed message
        assertThat(parsedAny.getTypeUrl()).isEqualTo(any.getTypeUrl());
    }
}
