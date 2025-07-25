package io.apicurio.registry.rest.v2;

import com.google.common.hash.Hashing;
import io.apicurio.registry.auth.Authorized;
import io.apicurio.registry.auth.AuthorizedLevel;
import io.apicurio.registry.auth.AuthorizedStyle;
import io.apicurio.registry.content.ContentHandle;
import io.apicurio.registry.content.TypedContent;
import io.apicurio.registry.content.extract.ContentExtractor;
import io.apicurio.registry.content.extract.ExtractedMetaData;
import io.apicurio.registry.content.util.ContentTypeUtil;
import io.apicurio.registry.logging.Logged;
import io.apicurio.registry.logging.audit.Audited;
import io.apicurio.registry.metrics.health.liveness.ResponseErrorLivenessCheck;
import io.apicurio.registry.metrics.health.readiness.ResponseTimeoutReadinessCheck;
import io.apicurio.registry.model.BranchId;
import io.apicurio.registry.model.GA;
import io.apicurio.registry.model.GAV;
import io.apicurio.registry.model.VersionExpressionParser;
import io.apicurio.registry.rest.HeadersHack;
import io.apicurio.registry.rest.MissingRequiredParameterException;
import io.apicurio.registry.rest.ParametersConflictException;
import io.apicurio.registry.rest.RestConfig;
import io.apicurio.registry.rest.v2.beans.*;
import io.apicurio.registry.rules.RuleApplicationType;
import io.apicurio.registry.rules.RulesService;
import io.apicurio.registry.storage.RegistryStorage;
import io.apicurio.registry.storage.RegistryStorage.RetrievalBehavior;
import io.apicurio.registry.storage.dto.*;
import io.apicurio.registry.storage.error.*;
import io.apicurio.registry.storage.impl.sql.RegistryContentUtils;
import io.apicurio.registry.types.*;
import io.apicurio.registry.types.provider.ArtifactTypeUtilProvider;
import io.apicurio.registry.types.provider.ArtifactTypeUtilProviderFactory;
import io.apicurio.registry.util.ArtifactIdGenerator;
import io.apicurio.registry.util.ArtifactTypeUtil;
import io.apicurio.registry.utils.ArtifactIdValidator;
import io.apicurio.registry.utils.IoUtil;
import io.apicurio.registry.utils.JAXRSClientUtil;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptors;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.tuple.Pair;
import org.jose4j.base64url.Base64;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static io.apicurio.registry.logging.audit.AuditingConstants.*;
import static io.apicurio.registry.rest.v2.V2ApiUtil.defaultGroupIdToNull;

/**
 * Implements the {@link GroupsResource} JAX-RS interface.
 */
@ApplicationScoped
@Interceptors({ ResponseErrorLivenessCheck.class, ResponseTimeoutReadinessCheck.class })
@Logged
public class GroupsResourceImpl implements GroupsResource {

    private static final String EMPTY_CONTENT_ERROR_MESSAGE = "Empty content is not allowed.";
    @SuppressWarnings("unused")
    private static final Integer GET_GROUPS_LIMIT = 1000;

    @Inject
    RulesService rulesService;

    @Inject
    ArtifactIdGenerator idGenerator;

    @Inject
    RestConfig restConfig;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    @Current
    RegistryStorage storage;

    @Inject
    ArtifactTypeUtilProviderFactory factory;

    @Inject
    io.apicurio.registry.rest.v3.GroupsResourceImpl v3;

    @Context
    HttpServletRequest request;

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#getLatestArtifact(java.lang.String, java.lang.String,
     *      Boolean)
     */
    @Override
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Read)
    public Response getLatestArtifact(String groupId, String artifactId, Boolean dereference) {
        requireParameter("groupId", groupId);
        requireParameter("artifactId", artifactId);

        if (dereference == null) {
            dereference = Boolean.FALSE;
        }

        try {
            GAV latestGAV = storage.getBranchTip(new GA(groupId, artifactId), BranchId.LATEST,
                    RetrievalBehavior.ACTIVE_STATES);
            ArtifactVersionMetaDataDto metaData = storage.getArtifactVersionMetaData(
                    latestGAV.getRawGroupIdWithNull(), latestGAV.getRawArtifactId(),
                    latestGAV.getRawVersionId());
            StoredArtifactVersionDto artifact = storage.getArtifactVersionContent(
                    defaultGroupIdToNull(groupId), artifactId, latestGAV.getRawVersionId());

            TypedContent contentToReturn = TypedContent.create(artifact.getContent(),
                    artifact.getContentType());

            ArtifactTypeUtilProvider artifactTypeProvider = factory
                    .getArtifactTypeProvider(metaData.getArtifactType());

            if (dereference && !artifact.getReferences().isEmpty()) {
                if (artifactTypeProvider.supportsReferencesWithContext()) {
                    RegistryContentUtils.RewrittenContentHolder rewrittenContent = RegistryContentUtils
                            .recursivelyResolveReferencesWithContext(contentToReturn,
                                    metaData.getArtifactType(), artifact.getReferences(),
                                    storage::getContentByReference);

                    contentToReturn = artifactTypeProvider.getContentDereferencer().dereference(
                            rewrittenContent.getRewrittenContent(), rewrittenContent.getResolvedReferences());
                } else {
                    contentToReturn = artifactTypeProvider.getContentDereferencer()
                            .dereference(contentToReturn, RegistryContentUtils.recursivelyResolveReferences(
                                    artifact.getReferences(), storage::getContentByReference));
                }
            }

            Response.ResponseBuilder builder = Response.ok(contentToReturn.getContent(),
                    contentToReturn.getContentType());
            checkIfDeprecated(metaData::getState, groupId, artifactId, metaData.getVersion(), builder);
            return builder.build();
        } catch (VersionNotFoundException e) {
            throw new ArtifactNotFoundException(e.getGroupId(), e.getArtifactId());
        }
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#updateArtifact(String, String, String, String, String,
     *      String, String, InputStream)
     */
    @Override
    @Audited(extractParameters = { "0", KEY_GROUP_ID, "1", KEY_ARTIFACT_ID, "2", KEY_VERSION, "3", KEY_NAME,
            "4", KEY_NAME_ENCODED, "5", KEY_DESCRIPTION, "6", KEY_DESCRIPTION_ENCODED })
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Write)
    public ArtifactMetaData updateArtifact(String groupId, String artifactId, String xRegistryVersion,
            String xRegistryName, String xRegistryNameEncoded, String xRegistryDescription,
            String xRegistryDescriptionEncoded, InputStream data) {
        return this.updateArtifactWithRefs(groupId, artifactId, xRegistryVersion, xRegistryName,
                xRegistryNameEncoded, xRegistryDescription, xRegistryDescriptionEncoded, data,
                Collections.emptyList());
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#updateArtifact(java.lang.String, java.lang.String,
     *      java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String,
     *      io.apicurio.registry.rest.v2.beans.ArtifactContent)
     */
    @Override
    @Audited(extractParameters = { "0", KEY_GROUP_ID, "1", KEY_ARTIFACT_ID, "2", KEY_VERSION, "3", KEY_NAME,
            "4", KEY_NAME_ENCODED, "5", KEY_DESCRIPTION, "6", KEY_DESCRIPTION_ENCODED })
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Write)
    public ArtifactMetaData updateArtifact(String groupId, String artifactId, String xRegistryVersion,
            String xRegistryName, String xRegistryNameEncoded, String xRegistryDescription,
            String xRegistryDescriptionEncoded, ArtifactContent data) {
        requireParameter("content", data.getContent());
        return this.updateArtifactWithRefs(groupId, artifactId, xRegistryVersion, xRegistryName,
                xRegistryNameEncoded, xRegistryDescription, xRegistryDescriptionEncoded,
                IoUtil.toStream(data.getContent()), data.getReferences());
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#getArtifactVersionReferences(java.lang.String,
     *      java.lang.String, java.lang.String, io.apicurio.registry.types.ReferenceType)
     */
    @Override
    public List<ArtifactReference> getArtifactVersionReferences(String groupId, String artifactId,
            String version, ReferenceType refType) {

        if ("latest".equals(version)) {
            var gav = VersionExpressionParser.parse(new GA(groupId, artifactId), "branch=latest",
                    (ga, branchId) -> storage.getBranchTip(ga, branchId, RetrievalBehavior.ALL_STATES));
            version = gav.getRawVersionId();
        }

        if (refType == null || refType == ReferenceType.OUTBOUND) {
            return storage.getArtifactVersionContent(defaultGroupIdToNull(groupId), artifactId, version)
                    .getReferences().stream().map(V2ApiUtil::referenceDtoToReference)
                    .collect(Collectors.toList());
        } else {
            return storage.getInboundArtifactReferences(defaultGroupIdToNull(groupId), artifactId, version)
                    .stream().map(V2ApiUtil::referenceDtoToReference).collect(Collectors.toList());
        }
    }

    private ArtifactMetaData updateArtifactWithRefs(String groupId, String artifactId,
            String xRegistryVersion, String xRegistryName, String xRegistryNameEncoded,
            String xRegistryDescription, String xRegistryDescriptionEncoded, InputStream data,
            List<ArtifactReference> references) {

        requireParameter("groupId", groupId);
        requireParameter("artifactId", artifactId);

        maxOneOf("X-Registry-Name", xRegistryName, "X-Registry-Name-Encoded", xRegistryNameEncoded);
        maxOneOf("X-Registry-Description", xRegistryDescription, "X-Registry-Description-Encoded",
                xRegistryDescriptionEncoded);

        String artifactName = getOneOf(xRegistryName, decode(xRegistryNameEncoded));
        String artifactDescription = getOneOf(xRegistryDescription, decode(xRegistryDescriptionEncoded));

        ContentHandle content = ContentHandle.create(data);
        if (content.bytes().length == 0) {
            throw new BadRequestException(EMPTY_CONTENT_ERROR_MESSAGE);
        }
        return updateArtifactInternal(groupId, artifactId, xRegistryVersion, artifactName,
                artifactDescription, content, getContentType(), references);
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#deleteArtifact(java.lang.String, java.lang.String)
     */
    @Override
    @Audited(extractParameters = { "0", KEY_GROUP_ID, "1", KEY_ARTIFACT_ID })
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Write)
    public void deleteArtifact(String groupId, String artifactId) {
        if (!restConfig.isArtifactDeletionEnabled()) {
            throw new NotAllowedException("Artifact deletion operation is not enabled.", HttpMethod.GET,
                    (String[]) null);
        }

        requireParameter("groupId", groupId);
        requireParameter("artifactId", artifactId);

        storage.deleteArtifact(defaultGroupIdToNull(groupId), artifactId);
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#getArtifactMetaData(java.lang.String,
     *      java.lang.String)
     */
    @Override
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Read)
    public ArtifactMetaData getArtifactMetaData(String groupId, String artifactId) {
        requireParameter("groupId", groupId);
        requireParameter("artifactId", artifactId);

        ArtifactMetaDataDto dto = storage.getArtifactMetaData(defaultGroupIdToNull(groupId), artifactId);
        GAV latestGAV = storage.getBranchTip(new GA(groupId, artifactId), BranchId.LATEST,
                RetrievalBehavior.ACTIVE_STATES);
        ArtifactVersionMetaDataDto vdto = storage.getArtifactVersionMetaData(
                latestGAV.getRawGroupIdWithNull(), latestGAV.getRawArtifactId(), latestGAV.getRawVersionId());

        ArtifactMetaData amd = V2ApiUtil.dtoToMetaData(defaultGroupIdToNull(groupId), artifactId,
                dto.getArtifactType(), dto);
        amd.setContentId(vdto.getContentId());
        amd.setGlobalId(vdto.getGlobalId());
        amd.setVersion(vdto.getVersion());
        amd.setName(vdto.getName());
        amd.setDescription(vdto.getDescription());
        amd.setModifiedBy(vdto.getOwner());
        amd.setModifiedOn(new Date(vdto.getCreatedOn()));
        amd.setLabels(V2ApiUtil.toV2Labels(vdto.getLabels()));
        amd.setProperties(V2ApiUtil.toV2Properties(vdto.getLabels()));
        amd.setState(ArtifactState.fromValue(vdto.getState().name()));
        return amd;
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#updateArtifactMetaData(java.lang.String,
     *      java.lang.String, io.apicurio.registry.rest.v2.beans.EditableMetaData)
     */
    @Override
    @Audited(extractParameters = { "0", KEY_GROUP_ID, "1", KEY_ARTIFACT_ID, "2", KEY_EDITABLE_METADATA })
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Write)
    public void updateArtifactMetaData(String groupId, String artifactId, EditableMetaData data) {
        GAV latestGAV = storage.getBranchTip(new GA(groupId, artifactId), BranchId.LATEST,
                RetrievalBehavior.ALL_STATES);
        storage.updateArtifactVersionMetaData(groupId, artifactId, latestGAV.getRawVersionId(),
                EditableVersionMetaDataDto.builder().name(data.getName()).description(data.getDescription())
                        .labels(V2ApiUtil.toV3Labels(data.getLabels(), data.getProperties())).build());
    }

    @Override
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Read)
    public ArtifactOwner getArtifactOwner(String groupId, String artifactId) {
        requireParameter("groupId", groupId);
        requireParameter("artifactId", artifactId);

        ArtifactMetaDataDto dto = storage.getArtifactMetaData(defaultGroupIdToNull(groupId), artifactId);
        ArtifactOwner owner = new ArtifactOwner();
        owner.setOwner(dto.getOwner());
        return owner;
    }

    @Override
    @Audited(extractParameters = { "0", KEY_GROUP_ID, "1", KEY_ARTIFACT_ID, "2", KEY_OWNER })
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.AdminOrOwner)
    public void updateArtifactOwner(String groupId, String artifactId, ArtifactOwner data) {
        requireParameter("groupId", groupId);
        requireParameter("artifactId", artifactId);
        requireParameter("data", data);

        if (data.getOwner().isEmpty()) {
            throw new MissingRequiredParameterException("Missing required owner");
        }

        EditableArtifactMetaDataDto emd = EditableArtifactMetaDataDto.builder().owner(data.getOwner())
                .build();
        storage.updateArtifactMetaData(defaultGroupIdToNull(groupId), artifactId, emd);
    }

    @Override
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Read)
    public GroupMetaData getGroupById(String groupId) {
        GroupMetaDataDto group = storage.getGroupMetaData(groupId);
        return V2ApiUtil.groupDtoToGroup(group);
    }

    @Override
    @Authorized(style = AuthorizedStyle.GroupOnly, level = AuthorizedLevel.Write)
    public void deleteGroupById(String groupId) {
        if (!restConfig.isGroupDeletionEnabled()) {
            throw new NotAllowedException("Group deletion operation is not enabled.", HttpMethod.GET,
                    (String[]) null);
        }

        storage.deleteGroup(groupId);
    }

    @Override
    @Authorized(style = AuthorizedStyle.None, level = AuthorizedLevel.Read)
    public GroupSearchResults listGroups(BigInteger limit, BigInteger offset, SortOrder order,
            SortBy orderby) {
        if (orderby == null) {
            orderby = SortBy.name;
        }
        if (offset == null) {
            offset = BigInteger.valueOf(0);
        }
        if (limit == null) {
            limit = BigInteger.valueOf(20);
        }

        final OrderBy oBy = OrderBy.valueOf(orderby.name());
        final OrderDirection oDir = order == null || order == SortOrder.asc ? OrderDirection.asc
            : OrderDirection.desc;

        Set<SearchFilter> filters = Collections.emptySet();

        GroupSearchResultsDto resultsDto = storage.searchGroups(filters, oBy, oDir, offset.intValue(),
                limit.intValue());
        return V2ApiUtil.dtoToSearchResults(resultsDto);
    }

    @Override
    @Authorized(style = AuthorizedStyle.None, level = AuthorizedLevel.Write)
    public GroupMetaData createGroup(CreateGroupMetaData data) {
        GroupMetaDataDto.GroupMetaDataDtoBuilder group = GroupMetaDataDto.builder().groupId(data.getId())
                .description(data.getDescription()).labels(data.getProperties());

        String user = securityIdentity.getPrincipal().getName();
        group.owner(user).createdOn(new Date().getTime());

        storage.createGroup(group.build());

        return V2ApiUtil.groupDtoToGroup(storage.getGroupMetaData(data.getId()));
    }

    @Override
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Read)
    public VersionMetaData getArtifactVersionMetaDataByContent(String groupId, String artifactId,
            Boolean canonical, ArtifactContent artifactContent) {
        return getArtifactVersionMetaDataByContent(groupId, artifactId, canonical,
                IoUtil.toStream(artifactContent.getContent()), artifactContent.getReferences());
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#getArtifactVersionMetaDataByContent(java.lang.String,
     *      java.lang.String, java.lang.Boolean, java.io.InputStream)
     */
    @Override
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Read)
    public VersionMetaData getArtifactVersionMetaDataByContent(String groupId, String artifactId,
            Boolean canonical, InputStream data) {
        return getArtifactVersionMetaDataByContent(groupId, artifactId, canonical, data,
                Collections.emptyList());
    }

    private VersionMetaData getArtifactVersionMetaDataByContent(String groupId, String artifactId,
            Boolean canonical, InputStream data, List<ArtifactReference> artifactReferences) {
        requireParameter("groupId", groupId);
        requireParameter("artifactId", artifactId);

        if (canonical == null) {
            canonical = Boolean.FALSE;
        }

        String contentType = getContentType();
        ContentHandle content = ContentHandle.create(data);
        if (content.bytes().length == 0) {
            throw new BadRequestException(EMPTY_CONTENT_ERROR_MESSAGE);
        }
        if (ContentTypeUtil.isApplicationYaml(getContentType())) {
            content = ContentTypeUtil.yamlToJson(content);
            contentType = ContentTypes.APPLICATION_JSON;
        }

        final List<ArtifactReferenceDto> artifactReferenceDtos = toReferenceDtos(artifactReferences);

        TypedContent typedContent = TypedContent.create(content, contentType);
        ArtifactVersionMetaDataDto dto = storage.getArtifactVersionMetaDataByContent(
                defaultGroupIdToNull(groupId), artifactId, canonical, typedContent, artifactReferenceDtos);
        return V2ApiUtil.dtoToVersionMetaData(defaultGroupIdToNull(groupId), artifactId,
                dto.getArtifactType(), dto);
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#listArtifactRules(java.lang.String, java.lang.String)
     */
    @Override
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Read)
    public List<RuleType> listArtifactRules(String groupId, String artifactId) {
        requireParameter("groupId", groupId);
        requireParameter("artifactId", artifactId);

        return storage.getArtifactRules(defaultGroupIdToNull(groupId), artifactId);
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#createArtifactRule(java.lang.String, java.lang.String,
     *      io.apicurio.registry.rest.v2.beans.Rule)
     */
    @Override
    @Audited(extractParameters = { "0", KEY_GROUP_ID, "1", KEY_ARTIFACT_ID, "2", KEY_RULE })
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Write)
    public void createArtifactRule(String groupId, String artifactId, Rule data) {
        requireParameter("groupId", groupId);
        requireParameter("artifactId", artifactId);

        RuleType type = data.getType();
        requireParameter("type", type);

        if (data.getConfig() == null || data.getConfig().isEmpty()) {
            throw new MissingRequiredParameterException("Config");
        }

        RuleConfigurationDto config = new RuleConfigurationDto();
        config.setConfiguration(data.getConfig());

        if (!storage.isArtifactExists(defaultGroupIdToNull(groupId), artifactId)) {
            throw new ArtifactNotFoundException(groupId, artifactId);
        }

        storage.createArtifactRule(defaultGroupIdToNull(groupId), artifactId, data.getType(), config);
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#deleteArtifactRules(java.lang.String,
     *      java.lang.String)
     */
    @Override
    @Audited(extractParameters = { "0", KEY_GROUP_ID, "1", KEY_ARTIFACT_ID })
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Write)
    public void deleteArtifactRules(String groupId, String artifactId) {
        requireParameter("groupId", groupId);
        requireParameter("artifactId", artifactId);

        storage.deleteArtifactRules(defaultGroupIdToNull(groupId), artifactId);
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#getArtifactRuleConfig(java.lang.String,
     *      java.lang.String, io.apicurio.registry.types.RuleType)
     */
    @Override
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Read)
    public Rule getArtifactRuleConfig(String groupId, String artifactId, RuleType rule) {
        requireParameter("groupId", groupId);
        requireParameter("artifactId", artifactId);
        requireParameter("rule", rule);

        RuleConfigurationDto dto = storage.getArtifactRule(defaultGroupIdToNull(groupId), artifactId, rule);
        Rule rval = new Rule();
        rval.setConfig(dto.getConfiguration());
        rval.setType(rule);
        return rval;
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#updateArtifactRuleConfig(java.lang.String,
     *      java.lang.String, io.apicurio.registry.types.RuleType, io.apicurio.registry.rest.v2.beans.Rule)
     */
    @Override
    @Audited(extractParameters = { "0", KEY_GROUP_ID, "1", KEY_ARTIFACT_ID, "2", KEY_RULE_TYPE, "3",
            KEY_RULE })
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Write)
    public Rule updateArtifactRuleConfig(String groupId, String artifactId, RuleType rule, Rule data) {
        requireParameter("groupId", groupId);
        requireParameter("artifactId", artifactId);
        requireParameter("rule", rule);

        RuleConfigurationDto dto = new RuleConfigurationDto(data.getConfig());
        storage.updateArtifactRule(defaultGroupIdToNull(groupId), artifactId, rule, dto);
        Rule rval = new Rule();
        rval.setType(rule);
        rval.setConfig(data.getConfig());
        return rval;
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#deleteArtifactRule(java.lang.String, java.lang.String,
     *      io.apicurio.registry.types.RuleType)
     */
    @Override
    @Audited(extractParameters = { "0", KEY_GROUP_ID, "1", KEY_ARTIFACT_ID, "2", KEY_RULE_TYPE })
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Write)
    public void deleteArtifactRule(String groupId, String artifactId, RuleType rule) {
        requireParameter("groupId", groupId);
        requireParameter("artifactId", artifactId);
        requireParameter("rule", rule);

        storage.deleteArtifactRule(defaultGroupIdToNull(groupId), artifactId, rule);
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#updateArtifactState(java.lang.String,
     *      java.lang.String, io.apicurio.registry.rest.v2.beans.UpdateState)
     */
    @Override
    @Audited(extractParameters = { "0", KEY_GROUP_ID, "1", KEY_ARTIFACT_ID, "2", KEY_UPDATE_STATE })
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Write)
    public void updateArtifactState(String groupId, String artifactId, UpdateState data) {
        requireParameter("groupId", groupId);
        requireParameter("artifactId", artifactId);
        requireParameter("body.state", data.getState());

        // Possible race condition here. Worst case should be that the update fails with a reasonable message.
        GAV latestGAV = storage.getBranchTip(new GA(defaultGroupIdToNull(groupId), artifactId),
                BranchId.LATEST, RetrievalBehavior.ALL_STATES);
        updateArtifactVersionState(groupId, artifactId, latestGAV.getRawVersionId(), data);
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#testUpdateArtifact(java.lang.String, java.lang.String,
     *      java.io.InputStream)
     */
    @Override
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Write)
    public void testUpdateArtifact(String groupId, String artifactId, InputStream data) {
        requireParameter("groupId", groupId);
        requireParameter("artifactId", artifactId);
        ContentHandle content = ContentHandle.create(data);
        if (content.bytes().length == 0) {
            throw new BadRequestException(EMPTY_CONTENT_ERROR_MESSAGE);
        }

        String ct = getContentType();
        if (ContentTypeUtil.isApplicationYaml(ct)) {
            content = ContentTypeUtil.yamlToJson(content);
            ct = ContentTypes.APPLICATION_JSON;
        }

        String artifactType = lookupArtifactType(groupId, artifactId);
        TypedContent typedContent = TypedContent.create(content, ct);
        rulesService.applyRules(defaultGroupIdToNull(groupId), artifactId, artifactType, typedContent,
                RuleApplicationType.UPDATE, Collections.emptyList(), Collections.emptyMap()); // TODO:references
                                                                                              // not supported
                                                                                              // for testing
                                                                                              // update
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#getArtifactVersion(String, String, String, Boolean)
     */
    @Override
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Read)
    public Response getArtifactVersion(String groupId, String artifactId, String version,
            Boolean dereference) {
        requireParameter("groupId", groupId);
        requireParameter("artifactId", artifactId);
        requireParameter("version", version);

        if (dereference == null) {
            dereference = Boolean.FALSE;
        }

        if ("latest".equals(version)) {
            var gav = VersionExpressionParser.parse(new GA(groupId, artifactId), "branch=latest",
                    (ga, branchId) -> storage.getBranchTip(ga, branchId, RetrievalBehavior.ALL_STATES));
            version = gav.getRawVersionId();
        }

        ArtifactVersionMetaDataDto metaData = storage
                .getArtifactVersionMetaData(defaultGroupIdToNull(groupId), artifactId, version);
        if (VersionState.DISABLED.equals(metaData.getState())) {
            throw new VersionNotFoundException(groupId, artifactId, version);
        }
        StoredArtifactVersionDto artifact = storage.getArtifactVersionContent(defaultGroupIdToNull(groupId),
                artifactId, version);

        TypedContent contentToReturn = TypedContent.create(artifact.getContent(), artifact.getContentType());

        ArtifactTypeUtilProvider artifactTypeProvider = factory
                .getArtifactTypeProvider(metaData.getArtifactType());

        if (dereference && !artifact.getReferences().isEmpty()) {
            if (artifactTypeProvider.supportsReferencesWithContext()) {
                RegistryContentUtils.RewrittenContentHolder rewrittenContent = RegistryContentUtils
                        .recursivelyResolveReferencesWithContext(contentToReturn, metaData.getArtifactType(),
                                artifact.getReferences(), storage::getContentByReference);

                contentToReturn = artifactTypeProvider.getContentDereferencer().dereference(
                        rewrittenContent.getRewrittenContent(), rewrittenContent.getResolvedReferences());
            } else {
                contentToReturn = artifactTypeProvider.getContentDereferencer().dereference(contentToReturn,
                        RegistryContentUtils.recursivelyResolveReferences(artifact.getReferences(),
                                storage::getContentByReference));
            }
        }

        Response.ResponseBuilder builder = Response.ok(contentToReturn.getContent(),
                contentToReturn.getContentType());
        checkIfDeprecated(metaData::getState, groupId, artifactId, version, builder);
        return builder.build();
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#deleteArtifactVersion(java.lang.String,
     *      java.lang.String, java.lang.String)
     */
    @Override
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Write)
    public void deleteArtifactVersion(String groupId, String artifactId, String version) {
        if (!restConfig.isArtifactVersionDeletionEnabled()) {
            throw new NotAllowedException("Artifact version deletion operation is not enabled.",
                    HttpMethod.GET, (String[]) null);
        }

        requireParameter("groupId", groupId);
        requireParameter("artifactId", artifactId);
        requireParameter("version", version);

        storage.deleteArtifactVersion(defaultGroupIdToNull(groupId), artifactId, version);
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#getArtifactVersionMetaData(java.lang.String,
     *      java.lang.String, java.lang.String)
     */
    @Override
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Read)
    public VersionMetaData getArtifactVersionMetaData(String groupId, String artifactId, String version) {
        requireParameter("groupId", groupId);
        requireParameter("artifactId", artifactId);
        requireParameter("version", version);

        if ("latest".equals(version)) {
            var gav = VersionExpressionParser.parse(new GA(groupId, artifactId), "branch=latest",
                    (ga, branchId) -> storage.getBranchTip(ga, branchId, RetrievalBehavior.ALL_STATES));
            version = gav.getRawVersionId();
        }

        ArtifactVersionMetaDataDto dto = storage.getArtifactVersionMetaData(defaultGroupIdToNull(groupId),
                artifactId, version);
        return V2ApiUtil.dtoToVersionMetaData(defaultGroupIdToNull(groupId), artifactId,
                dto.getArtifactType(), dto);
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#updateArtifactVersionMetaData(java.lang.String,
     *      java.lang.String, java.lang.String, io.apicurio.registry.rest.v2.beans.EditableMetaData)
     */
    @Override
    @Audited(extractParameters = { "0", KEY_GROUP_ID, "1", KEY_ARTIFACT_ID, "2", KEY_VERSION, "3",
            KEY_EDITABLE_METADATA })
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Write)
    public void updateArtifactVersionMetaData(String groupId, String artifactId, String version,
            EditableMetaData data) {
        v3.updateArtifactVersionMetaData(groupId, artifactId, version,
                io.apicurio.registry.rest.v3.beans.EditableVersionMetaData.builder()
                        .description(data.getDescription())
                        .labels(V2ApiUtil.toV3Labels(data.getLabels(), data.getProperties()))
                        .name(data.getName()).build());
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#deleteArtifactVersionMetaData(java.lang.String,
     *      java.lang.String, java.lang.String)
     */
    @Override
    @Audited(extractParameters = { "0", KEY_GROUP_ID, "1", KEY_ARTIFACT_ID, "2", KEY_VERSION })
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Write)
    public void deleteArtifactVersionMetaData(String groupId, String artifactId, String version) {
        requireParameter("groupId", groupId);
        requireParameter("artifactId", artifactId);
        requireParameter("version", version);

        EditableVersionMetaDataDto vmd = EditableVersionMetaDataDto.builder().name("").description("")
                .labels(Map.of()).build();
        storage.updateArtifactVersionMetaData(defaultGroupIdToNull(groupId), artifactId, version, vmd);
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#addArtifactVersionComment(java.lang.String,
     *      java.lang.String, java.lang.String, io.apicurio.registry.rest.v2.beans.NewComment)
     */
    @Override
    @Audited(extractParameters = { "0", KEY_GROUP_ID, "1", KEY_ARTIFACT_ID, "2", KEY_VERSION })
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Write)
    public Comment addArtifactVersionComment(String groupId, String artifactId, String version,
            NewComment data) {
        requireParameter("groupId", groupId);
        requireParameter("artifactId", artifactId);
        requireParameter("version", version);

        CommentDto newComment = storage.createArtifactVersionComment(defaultGroupIdToNull(groupId),
                artifactId, version, data.getValue());
        return V2ApiUtil.commentDtoToComment(newComment);
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#deleteArtifactVersionComment(java.lang.String,
     *      java.lang.String, java.lang.String, java.lang.String)
     */
    @Override
    @Audited(extractParameters = { "0", KEY_GROUP_ID, "1", KEY_ARTIFACT_ID, "2", KEY_VERSION, "3",
            "comment_id" }) // TODO
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Write)
    public void deleteArtifactVersionComment(String groupId, String artifactId, String version,
            String commentId) {
        requireParameter("groupId", groupId);
        requireParameter("artifactId", artifactId);
        requireParameter("version", version);
        requireParameter("commentId", commentId);

        storage.deleteArtifactVersionComment(defaultGroupIdToNull(groupId), artifactId, version, commentId);
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#getArtifactVersionComments(java.lang.String,
     *      java.lang.String, java.lang.String)
     */
    @Override
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Read)
    public List<Comment> getArtifactVersionComments(String groupId, String artifactId, String version) {
        requireParameter("groupId", groupId);
        requireParameter("artifactId", artifactId);
        requireParameter("version", version);

        if ("latest".equals(version)) {
            var gav = VersionExpressionParser.parse(new GA(groupId, artifactId), "branch=latest",
                    (ga, branchId) -> storage.getBranchTip(ga, branchId, RetrievalBehavior.ALL_STATES));
            version = gav.getRawVersionId();
        }

        return storage.getArtifactVersionComments(defaultGroupIdToNull(groupId), artifactId, version).stream()
                .map(V2ApiUtil::commentDtoToComment).collect(Collectors.toList());
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#updateArtifactVersionComment(java.lang.String,
     *      java.lang.String, java.lang.String, java.lang.String,
     *      io.apicurio.registry.rest.v2.beans.NewComment)
     */
    @Override
    @Audited(extractParameters = { "0", KEY_GROUP_ID, "1", KEY_ARTIFACT_ID, "2", KEY_VERSION, "3",
            "comment_id" }) // TODO
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Write)
    public void updateArtifactVersionComment(String groupId, String artifactId, String version,
            String commentId, NewComment data) {
        requireParameter("groupId", groupId);
        requireParameter("artifactId", artifactId);
        requireParameter("version", version);
        requireParameter("commentId", commentId);
        requireParameter("value", data.getValue());

        storage.updateArtifactVersionComment(defaultGroupIdToNull(groupId), artifactId, version, commentId,
                data.getValue());
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#updateArtifactVersionState(java.lang.String,
     *      java.lang.String, java.lang.String, io.apicurio.registry.rest.v2.beans.UpdateState)
     */
    @Override
    @Audited(extractParameters = { "0", KEY_GROUP_ID, "1", KEY_ARTIFACT_ID, "2", KEY_VERSION, "3",
            KEY_UPDATE_STATE })
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Write)
    public void updateArtifactVersionState(String groupId, String artifactId, String version,
            UpdateState data) {
        requireParameter("groupId", groupId);
        requireParameter("artifactId", artifactId);
        requireParameter("version", version);

        VersionState newState = VersionState.fromValue(data.getState().name());
        storage.updateArtifactVersionState(groupId, artifactId, version, newState, false);
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#listArtifactsInGroup(String, BigInteger, BigInteger,
     *      SortOrder, SortBy)
     */
    @Override
    @Authorized(style = AuthorizedStyle.GroupOnly, level = AuthorizedLevel.Read)
    public ArtifactSearchResults listArtifactsInGroup(String groupId, BigInteger limit, BigInteger offset,
            SortOrder order, SortBy orderby) {
        requireParameter("groupId", groupId);

        if (orderby == null) {
            orderby = SortBy.name;
        }
        if (offset == null) {
            offset = BigInteger.valueOf(0);
        }
        if (limit == null) {
            limit = BigInteger.valueOf(20);
        }

        final OrderBy oBy = OrderBy.valueOf(orderby.name());
        final OrderDirection oDir = order == null || order == SortOrder.asc ? OrderDirection.asc
            : OrderDirection.desc;

        Set<SearchFilter> filters = new HashSet<>();
        filters.add(SearchFilter.ofGroupId(defaultGroupIdToNull(groupId)));

        ArtifactSearchResultsDto resultsDto = storage.searchArtifacts(filters, oBy, oDir, offset.intValue(),
                limit.intValue());
        return V2ApiUtil.dtoToSearchResults(resultsDto);
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#deleteArtifactsInGroup(java.lang.String)
     */
    @Override
    @Audited(extractParameters = { "0", KEY_GROUP_ID })
    @Authorized(style = AuthorizedStyle.GroupOnly, level = AuthorizedLevel.Write)
    public void deleteArtifactsInGroup(String groupId) {
        if (!restConfig.isArtifactDeletionEnabled()) {
            throw new NotAllowedException("Artifact deletion operation is not enabled.", HttpMethod.GET,
                    (String[]) null);
        }

        requireParameter("groupId", groupId);

        storage.deleteArtifacts(defaultGroupIdToNull(groupId));
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#createArtifact(String, String, String, String,
     *      IfExists, Boolean, String, String, String, String, String, String, InputStream)
     */
    @Override
    @Audited(extractParameters = { "0", KEY_GROUP_ID, "1", KEY_ARTIFACT_TYPE, "2", KEY_ARTIFACT_ID, "3",
            KEY_VERSION, "4", KEY_IF_EXISTS, "5", KEY_CANONICAL, "6", KEY_DESCRIPTION, "7",
            KEY_DESCRIPTION_ENCODED, "8", KEY_NAME, "9", KEY_NAME_ENCODED, "10", KEY_FROM_URL, "11",
            KEY_SHA })
    @Authorized(style = AuthorizedStyle.GroupOnly, level = AuthorizedLevel.Write)
    public ArtifactMetaData createArtifact(String groupId, String xRegistryArtifactType,
            String xRegistryArtifactId, String xRegistryVersion, IfExists ifExists, Boolean canonical,
            String xRegistryDescription, String xRegistryDescriptionEncoded, String xRegistryName,
            String xRegistryNameEncoded, String xRegistryContentHash, String xRegistryHashAlgorithm,
            InputStream data) {
        return this.createArtifactWithRefs(groupId, xRegistryArtifactType, xRegistryArtifactId,
                xRegistryVersion, ifExists, canonical, xRegistryDescription, xRegistryDescriptionEncoded,
                xRegistryName, xRegistryNameEncoded, xRegistryContentHash, xRegistryHashAlgorithm, data,
                Collections.emptyList());
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#createArtifact(String, String, String, String,
     *      IfExists, Boolean, String, String, String, String, String, String, ArtifactContent)
     */
    @Override
    @Audited(extractParameters = { "0", KEY_GROUP_ID, "1", KEY_ARTIFACT_TYPE, "2", KEY_ARTIFACT_ID, "3",
            KEY_VERSION, "4", KEY_IF_EXISTS, "5", KEY_CANONICAL, "6", KEY_DESCRIPTION, "7",
            KEY_DESCRIPTION_ENCODED, "8", KEY_NAME, "9", KEY_NAME_ENCODED, "10", KEY_FROM_URL, "11",
            KEY_SHA })
    @Authorized(style = AuthorizedStyle.GroupOnly, level = AuthorizedLevel.Write)
    public ArtifactMetaData createArtifact(String groupId, String xRegistryArtifactType,
            String xRegistryArtifactId, String xRegistryVersion, IfExists ifExists, Boolean canonical,
            String xRegistryDescription, String xRegistryDescriptionEncoded, String xRegistryName,
            String xRegistryNameEncoded, String xRegistryContentHash, String xRegistryHashAlgorithm,
            ArtifactContent data) {
        requireParameter("content", data.getContent());

        Client client = null;
        InputStream content;
        try {
            try {
                URL url = new URL(data.getContent());
                client = JAXRSClientUtil.getJAXRSClient(restConfig.getDownloadSkipSSLValidation());
                content = fetchContentFromURL(client, url.toURI());
            } catch (MalformedURLException | URISyntaxException e) {
                content = IoUtil.toStream(data.getContent());
            }

            return this.createArtifactWithRefs(groupId, xRegistryArtifactType, xRegistryArtifactId,
                    xRegistryVersion, ifExists, canonical, xRegistryDescription, xRegistryDescriptionEncoded,
                    xRegistryName, xRegistryNameEncoded, xRegistryContentHash, xRegistryHashAlgorithm,
                    content, data.getReferences());
        } catch (KeyManagementException kme) {
            throw new RuntimeException(kme);
        } catch (NoSuchAlgorithmException nsae) {
            throw new RuntimeException(nsae);
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    public enum RegistryHashAlgorithm {
        SHA256, MD5
    }

    /**
     * Return an InputStream for the resource to be downloaded
     *
     * @param url
     */
    private InputStream fetchContentFromURL(Client client, URI url) {
        try {
            // 1. Registry issues HTTP HEAD request to the target URL.
            List<Object> contentLengthHeaders = client.target(url).request().head().getHeaders()
                    .get("Content-Length");

            if (contentLengthHeaders == null || contentLengthHeaders.size() < 1) {
                throw new BadRequestException(
                        "Requested resource URL does not provide 'Content-Length' in the headers");
            }

            // 2. According to HTTP specification, target server must return Content-Length header.
            int contentLength = Integer.parseInt(contentLengthHeaders.get(0).toString());

            // 3. Registry analyzes value of Content-Length to check if file with declared size could be
            // processed securely.
            if (contentLength > restConfig.getDownloadMaxSize()) {
                throw new BadRequestException("Requested resource is bigger than "
                        + restConfig.getDownloadMaxSize() + " and cannot be downloaded.");
            }

            if (contentLength <= 0) {
                throw new BadRequestException("Requested resource URL is providing 'Content-Length' <= 0.");
            }

            // 4. Finally, registry issues HTTP GET to the target URL and fetches only amount of bytes
            // specified by HTTP HEAD from step 1.
            return new BufferedInputStream(client.target(url).request().get().readEntity(InputStream.class),
                    contentLength);
        } catch (BadRequestException bre) {
            throw bre;
        } catch (Exception e) {
            throw new BadRequestException("Errors downloading the artifact content.", e);
        }
    }

    /**
     * Creates an artifact with references. Shared by both variants of createArtifact.
     *
     * @param groupId
     * @param xRegistryArtifactType
     * @param xRegistryArtifactId
     * @param xRegistryVersion
     * @param ifExists
     * @param canonical
     * @param xRegistryDescription
     * @param xRegistryDescriptionEncoded
     * @param xRegistryName
     * @param xRegistryNameEncoded
     * @param xRegistryContentHash
     * @param xRegistryHashAlgorithm
     * @param data
     * @param references
     */
    @SuppressWarnings("deprecation")
    private ArtifactMetaData createArtifactWithRefs(String groupId, String xRegistryArtifactType,
            String xRegistryArtifactId, String xRegistryVersion, IfExists ifExists, Boolean canonical,
            String xRegistryDescription, String xRegistryDescriptionEncoded, String xRegistryName,
            String xRegistryNameEncoded, String xRegistryContentHash, String xRegistryHashAlgorithm,
            InputStream data, List<ArtifactReference> references) {

        requireParameter("groupId", groupId);

        maxOneOf("X-Registry-Name", xRegistryName, "X-Registry-Name-Encoded", xRegistryNameEncoded);
        maxOneOf("X-Registry-Description", xRegistryDescription, "X-Registry-Description-Encoded",
                xRegistryDescriptionEncoded);

        String artifactName = getOneOf(xRegistryName, decode(xRegistryNameEncoded));
        String artifactDescription = getOneOf(xRegistryDescription, decode(xRegistryDescriptionEncoded));

        if (!ArtifactIdValidator.isGroupIdAllowed(groupId)) {
            throw new InvalidGroupIdException(ArtifactIdValidator.GROUP_ID_ERROR_MESSAGE);
        }

        // TODO do something with the optional user-provided Version

        ContentHandle content = ContentHandle.create(data);
        if (content.bytes().length == 0) {
            throw new BadRequestException(EMPTY_CONTENT_ERROR_MESSAGE);
        }

        // Mitigation for MITM attacks, verify that the artifact is the expected one
        if (xRegistryContentHash != null) {
            String calculatedSha = null;
            try {
                RegistryHashAlgorithm algorithm = (xRegistryHashAlgorithm == null)
                    ? RegistryHashAlgorithm.SHA256 : RegistryHashAlgorithm.valueOf(xRegistryHashAlgorithm);
                switch (algorithm) {
                    case MD5:
                        calculatedSha = Hashing.md5().hashString(content.content(), StandardCharsets.UTF_8)
                                .toString();
                        break;
                    case SHA256:
                        calculatedSha = Hashing.sha256().hashString(content.content(), StandardCharsets.UTF_8)
                                .toString();
                        break;
                }
            } catch (Exception e) {
                throw new BadRequestException("Requested hash algorithm not supported");
            }

            if (!calculatedSha.equals(xRegistryContentHash.trim())) {
                throw new BadRequestException("Provided Artifact Hash doesn't match with the content");
            }
        }

        final boolean fcanonical = canonical == null ? Boolean.FALSE : canonical;

        String ct = getContentType();
        if (ContentTypeUtil.isApplicationYaml(ct) || (ContentTypeUtil.isApplicationCreateExtended(ct)
                && ContentTypeUtil.isParsableYaml(content))) {
            content = ContentTypeUtil.yamlToJson(content);
            ct = ContentTypes.APPLICATION_JSON;
        } else {
            // Determine the content-type to *store* by examining the content.  In v2 we cannot rely on
            // the content-type provided by the client.
            if (ct == null || ContentTypeUtil.isApplicationCreateExtended(ct) || ContentTypeUtil.isTextPlain(ct)) {
                ct = ContentTypeUtil.determineContentType(content);
            }
        }

        try {
            String owner = securityIdentity.getPrincipal().getName();
            String artifactId = xRegistryArtifactId;

            if (artifactId == null || artifactId.trim().isEmpty()) {
                artifactId = idGenerator.generate();
            } else if (!ArtifactIdValidator.isArtifactIdAllowed(artifactId)) {
                throw new InvalidArtifactIdException(ArtifactIdValidator.ARTIFACT_ID_ERROR_MESSAGE);
            }

            TypedContent typedContent = TypedContent.create(content, ct);
            String artifactType = ArtifactType.AVRO;
            try {
                artifactType = ArtifactTypeUtil.determineArtifactType(typedContent, xRegistryArtifactType,
                        factory);
            } catch (InvalidArtifactTypeException e) {
                // Ignore this exception and default to AVRO.  This is what v2 did.
            }

            final List<ArtifactReferenceDto> referencesAsDtos = toReferenceDtos(references);

            // Try to resolve the new artifact references and the nested ones (if any)
            final Map<String, TypedContent> resolvedReferences = RegistryContentUtils
                    .recursivelyResolveReferences(referencesAsDtos, storage::getContentByReference);

            rulesService.applyRules(defaultGroupIdToNull(groupId), artifactId, artifactType, typedContent,
                    RuleApplicationType.CREATE, toV3Refs(references), resolvedReferences);

            // Extract metadata from content, then override extracted values with provided values.
            EditableArtifactMetaDataDto metaData = extractMetaData(artifactType, content);
            if (artifactName != null && artifactName.trim().isEmpty()) {
                metaData.setName(artifactName);
            }
            if (artifactDescription != null && artifactDescription.trim().isEmpty()) {
                metaData.setDescription(artifactDescription);
            }

            ContentWrapperDto contentDto = ContentWrapperDto.builder().contentType(ct).content(content)
                    .references(referencesAsDtos).build();
            EditableVersionMetaDataDto versionMetaData = EditableVersionMetaDataDto.builder()
                    .name(metaData.getName()).description(metaData.getDescription()).labels(Map.of()).build();

            Pair<ArtifactMetaDataDto, ArtifactVersionMetaDataDto> createResult = storage.createArtifact(
                    defaultGroupIdToNull(groupId), artifactId, artifactType, metaData, xRegistryVersion,
                    contentDto, versionMetaData, List.of(), false, false, owner);

            return V2ApiUtil.dtoToMetaData(groupId, artifactId, artifactType, createResult.getRight());
        } catch (ArtifactAlreadyExistsException ex) {
            return handleIfExists(groupId, xRegistryArtifactId, xRegistryVersion, ifExists, artifactName,
                    artifactDescription, content, ct, fcanonical, references);
        }
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#listArtifactVersions(String, String, BigInteger,
     *      BigInteger)
     */
    @Override
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Read)
    public VersionSearchResults listArtifactVersions(String groupId, String artifactId, BigInteger offset,
            BigInteger limit) {
        requireParameter("groupId", groupId);
        requireParameter("artifactId", artifactId);

        // This will check if the artifact exists (throws 404 if not).
        storage.getArtifactMetaData(defaultGroupIdToNull(groupId), artifactId);

        if (offset == null) {
            offset = BigInteger.valueOf(0);
        }
        if (limit == null) {
            limit = BigInteger.valueOf(20);
        }

        Set<SearchFilter> filters = Set.of(SearchFilter.ofGroupId(defaultGroupIdToNull(groupId)),
                SearchFilter.ofArtifactId(artifactId));
        VersionSearchResultsDto resultsDto = storage.searchVersions(filters, OrderBy.createdOn,
                OrderDirection.asc, offset.intValue(), limit.intValue());
        return V2ApiUtil.dtoToSearchResults(resultsDto);
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#createArtifactVersion(String, String, String, String,
     *      String, String, String, InputStream)
     */
    @Override
    @Audited(extractParameters = { "0", KEY_GROUP_ID, "1", KEY_ARTIFACT_ID, "2", KEY_VERSION, "3", KEY_NAME,
            "4", KEY_DESCRIPTION, "5", KEY_DESCRIPTION_ENCODED, "6", KEY_NAME_ENCODED })
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Write)
    public VersionMetaData createArtifactVersion(String groupId, String artifactId, String xRegistryVersion,
            String xRegistryName, String xRegistryDescription, String xRegistryDescriptionEncoded,
            String xRegistryNameEncoded, InputStream data) {
        return this.createArtifactVersionWithRefs(groupId, artifactId, xRegistryVersion, xRegistryName,
                xRegistryDescription, xRegistryDescriptionEncoded, xRegistryNameEncoded, data,
                Collections.emptyList());
    }

    /**
     * @see io.apicurio.registry.rest.v2.GroupsResource#createArtifactVersion(java.lang.String,
     *      java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String,
     *      java.lang.String, io.apicurio.registry.rest.v2.beans.ArtifactContent)
     */
    @Override
    @Audited(extractParameters = { "0", KEY_GROUP_ID, "1", KEY_ARTIFACT_ID, "2", KEY_VERSION, "3", KEY_NAME,
            "4", KEY_DESCRIPTION, "5", KEY_DESCRIPTION_ENCODED, "6", KEY_NAME_ENCODED })
    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Write)
    public VersionMetaData createArtifactVersion(String groupId, String artifactId, String xRegistryVersion,
            String xRegistryName, String xRegistryDescription, String xRegistryDescriptionEncoded,
            String xRegistryNameEncoded, ArtifactContent data) {
        requireParameter("content", data.getContent());
        return this.createArtifactVersionWithRefs(groupId, artifactId, xRegistryVersion, xRegistryName,
                xRegistryDescription, xRegistryDescriptionEncoded, xRegistryNameEncoded,
                IoUtil.toStream(data.getContent()), data.getReferences());
    }

    /**
     * Creates an artifact version with references. Shared implementation for both variants of
     * createArtifactVersion.
     *
     * @param groupId
     * @param artifactId
     * @param xRegistryVersion
     * @param xRegistryName
     * @param xRegistryDescription
     * @param xRegistryDescriptionEncoded
     * @param xRegistryNameEncoded
     * @param data
     * @param references
     */
    private VersionMetaData createArtifactVersionWithRefs(String groupId, String artifactId,
            String xRegistryVersion, String xRegistryName, String xRegistryDescription,
            String xRegistryDescriptionEncoded, String xRegistryNameEncoded, InputStream data,
            List<ArtifactReference> references) {
        // TODO do something with the user-provided version info
        requireParameter("groupId", groupId);
        requireParameter("artifactId", artifactId);

        maxOneOf("X-Registry-Name", xRegistryName, "X-Registry-Name-Encoded", xRegistryNameEncoded);
        maxOneOf("X-Registry-Description", xRegistryDescription, "X-Registry-Description-Encoded",
                xRegistryDescriptionEncoded);

        String artifactName = getOneOf(xRegistryName, decode(xRegistryNameEncoded));
        String artifactDescription = getOneOf(xRegistryDescription, decode(xRegistryDescriptionEncoded));

        ContentHandle content = ContentHandle.create(data);
        if (content.bytes().length == 0) {
            throw new BadRequestException(EMPTY_CONTENT_ERROR_MESSAGE);
        }
        String ct = getContentType();
        // If the content type is YAML, convert it to JSON.
        if (ContentTypeUtil.isApplicationYaml(ct)) {
            content = ContentTypeUtil.yamlToJson(content);
            ct = ContentTypes.APPLICATION_JSON;
        } else {
            // Determine the content-type to *store* by examining the content.  In v2 we cannot rely on
            // the content-type provided by the client.
            if (ct == null || ContentTypeUtil.isApplicationCreateExtended(ct) || ContentTypeUtil.isTextPlain(ct)) {
                ct = ContentTypeUtil.determineContentType(content);
            }
        }

        // Transform the given references into dtos and set the contentId, this will also detect if any of the
        // passed references does not exist.
        final List<ArtifactReferenceDto> referencesAsDtos = toReferenceDtos(references);

        // Try to resolve the new artifact references and the nested ones (if any)
        final Map<String, TypedContent> resolvedReferences = RegistryContentUtils
                .recursivelyResolveReferences(referencesAsDtos, storage::getContentByReference);

        final String owner = securityIdentity.getPrincipal().getName();

        String artifactType = lookupArtifactType(groupId, artifactId);
        TypedContent typedContent = TypedContent.create(content, ct);
        rulesService.applyRules(defaultGroupIdToNull(groupId), artifactId, artifactType, typedContent,
                RuleApplicationType.UPDATE, toV3Refs(references), resolvedReferences);
        EditableVersionMetaDataDto metaData = getEditableVersionMetaData(artifactName, artifactDescription);
        ContentWrapperDto contentDto = ContentWrapperDto.builder().content(content).contentType(ct)
                .references(referencesAsDtos).build();
        ArtifactVersionMetaDataDto vmdDto = storage.createArtifactVersion(defaultGroupIdToNull(groupId),
                artifactId, xRegistryVersion, artifactType, contentDto, metaData, List.of(), false, false,
                owner);
        return V2ApiUtil.dtoToVersionMetaData(defaultGroupIdToNull(groupId), artifactId, artifactType,
                vmdDto);
    }

    /**
     * Check to see if the artifact version is deprecated.
     *
     * @param stateSupplier
     * @param groupId
     * @param artifactId
     * @param version
     * @param builder
     */
    private void checkIfDeprecated(Supplier<VersionState> stateSupplier, String groupId, String artifactId,
            String version, Response.ResponseBuilder builder) {
        HeadersHack.checkIfDeprecated(stateSupplier, groupId, artifactId, version, builder);
    }

    /**
     * Looks up the artifact type for the given artifact.
     *
     * @param groupId
     * @param artifactId
     */
    private String lookupArtifactType(String groupId, String artifactId) {
        return storage.getArtifactMetaData(defaultGroupIdToNull(groupId), artifactId).getArtifactType();
    }

    /**
     * Make sure this is ONLY used when request instance is active. e.g. in actual http request
     */
    private String getContentType() {
        return request.getContentType();
    }

    private static final void requireParameter(String parameterName, Object parameterValue) {
        if (parameterValue == null) {
            throw new MissingRequiredParameterException(parameterName);
        }
    }

    private static void maxOneOf(String parameterOneName, Object parameterOneValue, String parameterTwoName,
            Object parameterTwoValue) {
        if (parameterOneValue != null && parameterTwoValue != null) {
            throw new ParametersConflictException(parameterOneName, parameterTwoName);
        }
    }

    private static <T> T getOneOf(T parameterOneValue, T parameterTwoValue) {
        return parameterOneValue != null ? parameterOneValue : parameterTwoValue;
    }

    private static String decode(String encoded) {
        if (encoded == null) {
            return null;
        }
        return new String(Base64.decode(encoded));
    }

    private ArtifactMetaData handleIfExists(String groupId, String artifactId, String version,
            IfExists ifExists, String artifactName, String artifactDescription, ContentHandle content,
            String contentType, boolean canonical, List<ArtifactReference> references) {
        final ArtifactMetaData artifactMetaData = getArtifactMetaData(groupId, artifactId);
        if (ifExists == null) {
            ifExists = IfExists.FAIL;
        }

        switch (ifExists) {
            case UPDATE:
                return updateArtifactInternal(groupId, artifactId, version, artifactName, artifactDescription,
                        content, contentType, references);
            case RETURN:
                return artifactMetaData;
            case RETURN_OR_UPDATE:
                return handleIfExistsReturnOrUpdate(groupId, artifactId, version, artifactName,
                        artifactDescription, content, contentType, canonical, references);
            default:
                throw new ArtifactAlreadyExistsException(groupId, artifactId);
        }
    }

    private ArtifactMetaData handleIfExistsReturnOrUpdate(String groupId, String artifactId, String version,
            String artifactName, String artifactDescription, ContentHandle content, String contentType,
            boolean canonical, List<ArtifactReference> references) {
        try {
            TypedContent typedContent = TypedContent.create(content, contentType);
            ArtifactVersionMetaDataDto mdDto = this.storage.getArtifactVersionMetaDataByContent(
                    defaultGroupIdToNull(groupId), artifactId, canonical, typedContent,
                    toReferenceDtos(references));
            ArtifactMetaData md = V2ApiUtil.dtoToMetaData(defaultGroupIdToNull(groupId), artifactId, null,
                    mdDto);
            return md;
        } catch (ArtifactNotFoundException nfe) {
            // This is OK - we'll update the artifact if there is no matching content already there.
        }
        return updateArtifactInternal(groupId, artifactId, version, artifactName, artifactDescription,
                content, contentType, references);
    }

    @Authorized(style = AuthorizedStyle.GroupAndArtifact, level = AuthorizedLevel.Write)
    protected ArtifactMetaData updateArtifactInternal(String groupId, String artifactId, String version,
            String name, String description, ContentHandle content, String contentType,
            List<ArtifactReference> references) {

        if (ContentTypeUtil.isApplicationYaml(contentType)) {
            content = ContentTypeUtil.yamlToJson(content);
            contentType = ContentTypes.APPLICATION_JSON;
        }

        String artifactType = lookupArtifactType(groupId, artifactId);

        // Transform the given references into dtos and set the contentId, this will also detect if any of the
        // passed references does not exist.
        final List<ArtifactReferenceDto> referencesAsDtos = toReferenceDtos(references);

        final Map<String, TypedContent> resolvedReferences = RegistryContentUtils
                .recursivelyResolveReferences(referencesAsDtos, storage::getContentByReference);

        TypedContent typedContent = TypedContent.create(content, contentType);
        rulesService.applyRules(defaultGroupIdToNull(groupId), artifactId, artifactType, typedContent,
                RuleApplicationType.UPDATE, toV3Refs(references), resolvedReferences);

        // Extract metadata from content, then override extracted values with provided values.
        EditableArtifactMetaDataDto artifactMD = extractMetaData(artifactType, content);
        if (name != null && name.trim().isEmpty()) {
            artifactMD.setName(name);
        }
        if (description != null && description.trim().isEmpty()) {
            artifactMD.setDescription(description);
        }

        final String owner = securityIdentity.getPrincipal().getName();

        EditableVersionMetaDataDto metaData = EditableVersionMetaDataDto.builder().name(artifactMD.getName())
                .description(artifactMD.getDescription()).labels(artifactMD.getLabels()).build();

        ContentWrapperDto contentDto = ContentWrapperDto.builder().content(content).contentType(contentType)
                .references(referencesAsDtos).build();
        ArtifactVersionMetaDataDto dto = storage.createArtifactVersion(defaultGroupIdToNull(groupId),
                artifactId, version, artifactType, contentDto, metaData, List.of(), false, false, owner);

        // Note: if the version was created, we need to update the artifact metadata as well, because
        // those are the semantics of the v2 API. :(
        storage.updateArtifactMetaData(defaultGroupIdToNull(groupId), artifactId, artifactMD);

        return V2ApiUtil.dtoToMetaData(defaultGroupIdToNull(groupId), artifactId, artifactType, dto);
    }

    private EditableArtifactMetaDataDto getEditableArtifactMetaData(String name, String description) {
        if (name != null || description != null) {
            return EditableArtifactMetaDataDto.builder().name(name).description(description).build();
        }
        return null;
    }

    private EditableVersionMetaDataDto getEditableVersionMetaData(String name, String description) {
        if (name != null || description != null) {
            return EditableVersionMetaDataDto.builder().name(name).description(description).build();
        }
        return null;
    }

    private List<ArtifactReferenceDto> toReferenceDtos(List<ArtifactReference> references) {
        if (references == null) {
            references = Collections.emptyList();
        }
        return references.stream().map(r -> {
            r.setGroupId(defaultGroupIdToNull(r.getGroupId()));
            return r;
        }) // .peek(...) may be optimized away
                .map(V2ApiUtil::referenceToDto).collect(Collectors.toList());
    }

    private static List<io.apicurio.registry.rest.v3.beans.ArtifactReference> toV3Refs(
            List<ArtifactReference> references) {
        return references.stream().map(ref -> toV3Ref(ref)).collect(Collectors.toList());
    }

    private static io.apicurio.registry.rest.v3.beans.ArtifactReference toV3Ref(ArtifactReference reference) {
        return io.apicurio.registry.rest.v3.beans.ArtifactReference.builder()
                .artifactId(reference.getArtifactId()).groupId(reference.getGroupId())
                .version(reference.getVersion()).name(reference.getName()).build();
    }

    protected EditableArtifactMetaDataDto extractMetaData(String artifactType, ContentHandle content) {
        ArtifactTypeUtilProvider provider = factory.getArtifactTypeProvider(artifactType);
        ContentExtractor extractor = provider.getContentExtractor();
        ExtractedMetaData emd = extractor.extract(content);
        EditableArtifactMetaDataDto metaData;
        if (emd != null) {
            metaData = new EditableArtifactMetaDataDto(emd.getName(), emd.getDescription(), null,
                    emd.getLabels());
        } else {
            metaData = new EditableArtifactMetaDataDto();
        }
        return metaData;
    }
}
