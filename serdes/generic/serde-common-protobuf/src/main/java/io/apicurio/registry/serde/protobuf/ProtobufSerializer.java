package io.apicurio.registry.serde.protobuf;

import com.google.protobuf.Message;
import io.apicurio.registry.protobuf.ProtobufDifference;
import io.apicurio.registry.resolver.ParsedSchema;
import io.apicurio.registry.resolver.SchemaParser;
import io.apicurio.registry.resolver.SchemaResolver;
import io.apicurio.registry.resolver.client.RegistryClientFacade;
import io.apicurio.registry.resolver.strategy.ArtifactReferenceResolverStrategy;
import io.apicurio.registry.rules.compatibility.protobuf.ProtobufCompatibilityCheckerLibrary;
import io.apicurio.registry.serde.AbstractSerializer;
import io.apicurio.registry.serde.config.SerdeConfig;
import io.apicurio.registry.serde.protobuf.ref.RefOuterClass.Ref;
import io.apicurio.registry.utils.protobuf.schema.ProtobufFile;
import io.apicurio.registry.utils.protobuf.schema.ProtobufSchema;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

public class ProtobufSerializer<U extends Message> extends AbstractSerializer<ProtobufSchema, U> {

    private Boolean validationEnabled;
    private ProtobufSchemaParser<U> parser = new ProtobufSchemaParser<>();

    private boolean writeRef = true;

    public ProtobufSerializer() {
        super();
    }

    public ProtobufSerializer(RegistryClientFacade clientFacade,
                              ArtifactReferenceResolverStrategy<ProtobufSchema, U> artifactResolverStrategy,
                              SchemaResolver<ProtobufSchema, U> schemaResolver) {
        super(clientFacade, artifactResolverStrategy, schemaResolver);
    }

    public ProtobufSerializer(RegistryClientFacade clientFacade) {
        super(clientFacade);
    }

    public ProtobufSerializer(SchemaResolver<ProtobufSchema, U> schemaResolver) {
        super(schemaResolver);
    }

    public ProtobufSerializer(RegistryClientFacade clientFacade, SchemaResolver<ProtobufSchema, U> schemaResolver) {
        super(clientFacade, schemaResolver);
    }

    public ProtobufSerializer(RegistryClientFacade clientFacade, SchemaResolver<ProtobufSchema, U> schemaResolver,
                              ArtifactReferenceResolverStrategy<ProtobufSchema, U> strategy) {
        super(clientFacade, strategy, schemaResolver);
    }

    @Override
    public void configure(SerdeConfig configs, boolean isKey) {
        ProtobufSerializerConfig config = new ProtobufSerializerConfig(configs.originals());
        super.configure(config, isKey);

        validationEnabled = config.validationEnabled();
    }

    /**
     * @see AbstractSerializer#schemaParser()
     */
    @Override
    public SchemaParser<ProtobufSchema, U> schemaParser() {
        return parser;
    }

    /**
     * @see io.apicurio.registry.serde.AbstractSerializer#serializeData(io.apicurio.registry.resolver.ParsedSchema,
     *      java.lang.Object, java.io.OutputStream)
     */
    @Override
    public void serializeData(ParsedSchema<ProtobufSchema> schema, U data, OutputStream out)
            throws IOException {
        if (validationEnabled) {

            if (schema.getParsedSchema() != null && schema.getParsedSchema().getFileDescriptor()
                    .findMessageTypeByName(data.getDescriptorForType().getName()) == null) {
                throw new IllegalStateException("Missing message type "
                        + data.getDescriptorForType().getName() + " in the protobuf schema");
            }

            List<ProtobufDifference> diffs = validate(schema, data);
            if (!diffs.isEmpty()) {
                throw new IllegalStateException(
                        "The data to send is not compatible with the schema. " + diffs);
            }

        }
        if (writeRef) {
            Ref ref = Ref.newBuilder().setName(data.getDescriptorForType().getName()).build();
            ref.writeDelimitedTo(out);
        }

        data.writeTo(out);
    }

    public void setWriteRef(boolean writeRef) {
        this.writeRef = writeRef;
    }

    private List<ProtobufDifference> validate(ParsedSchema<ProtobufSchema> schemaFromRegistry, U data) {
        ProtobufFile fileBefore = schemaFromRegistry.getParsedSchema().getProtobufFile();
        ProtobufFile fileAfter = new ProtobufFile(
                parser.toProtoFileElement(data.getDescriptorForType().getFile()));
        ProtobufCompatibilityCheckerLibrary checker = new ProtobufCompatibilityCheckerLibrary(fileBefore,
                fileAfter);
        return checker.findDifferences();
    }
}