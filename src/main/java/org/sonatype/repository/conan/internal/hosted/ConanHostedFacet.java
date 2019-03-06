/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2017-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.repository.conan.internal.hosted;

import java.io.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.IOUtils;
import org.apache.shiro.config.Ini;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.repository.Facet.Exposed;
import org.sonatype.nexus.repository.FacetSupport;
import org.sonatype.nexus.repository.http.HttpResponses;
import org.sonatype.nexus.repository.storage.*;
import org.sonatype.nexus.repository.transaction.TransactionalStoreBlob;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.matchers.token.TokenMatcher;
import org.sonatype.nexus.repository.view.payloads.StreamPayload;
import org.sonatype.nexus.repository.view.payloads.StreamPayload.InputStreamSupplier;
import org.sonatype.nexus.repository.view.payloads.StringPayload;
import org.sonatype.nexus.transaction.UnitOfWork;
import org.sonatype.repository.conan.internal.AssetKind;
import org.sonatype.repository.conan.internal.metadata.ConanCoords;
import org.sonatype.repository.conan.internal.metadata.ConanInfo;
import org.sonatype.repository.conan.internal.utils.ConanFacetUtils;

import com.google.common.base.Supplier;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Collections.singletonList;
import static org.sonatype.nexus.repository.http.HttpStatus.OK;
import static org.sonatype.nexus.repository.storage.AssetEntityAdapter.P_ASSET_KIND;
import static org.sonatype.nexus.repository.storage.ComponentEntityAdapter.P_GROUP;
import static org.sonatype.nexus.repository.storage.ComponentEntityAdapter.P_VERSION;
import static org.sonatype.nexus.repository.storage.MetadataNodeEntityAdapter.P_NAME;
import static org.sonatype.nexus.repository.view.Content.maintainLastModified;
import static org.sonatype.nexus.repository.view.ContentTypes.APPLICATION_JSON;
import static org.sonatype.nexus.repository.view.Status.success;
import static org.sonatype.repository.conan.internal.metadata.ConanMetadata.GROUP;
import static org.sonatype.repository.conan.internal.metadata.ConanMetadata.PROJECT;
import static org.sonatype.repository.conan.internal.metadata.ConanMetadata.STATE;
import static org.sonatype.repository.conan.internal.metadata.ConanMetadata.VERSION;
import static org.sonatype.repository.conan.internal.proxy.ConanProxyHelper.HASH_ALGORITHMS;
import static org.sonatype.repository.conan.internal.proxy.ConanProxyHelper.findAsset;
import static org.sonatype.repository.conan.internal.proxy.ConanProxyHelper.toContent;
import static org.sonatype.repository.conan.internal.utils.ConanFacetUtils.findComponent;

/**
 * @since 0.0.2
 */
@Exposed
@Named
public class ConanHostedFacet
        extends FacetSupport {
    private final UploadUrlManager uploadUrlManager;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    public ConanHostedFacet(final UploadUrlManager uploadUrlManager) {
        this.uploadUrlManager = uploadUrlManager;
    }

    /**
     * Services the upload_url endpoint which is basically the same as
     * the get of download_url.
     *
     * @param assetPath
     * @param coord
     * @param payload
     * @param assetKind
     * @return if successful content of the download_url is returned
     * @throws IOException
     */
    public Response uploadDownloadUrl(final String assetPath,
                                      final ConanCoords coord,
                                      final Payload payload,
                                      final AssetKind assetKind) throws IOException {
        checkNotNull(assetPath);
        checkNotNull(coord);
        checkNotNull(payload);
        checkNotNull(assetKind);

        String savedJson = getSavedJson(assetPath, payload);
        doPutArchive(assetPath + "/download_urls", coord, new StringPayload(savedJson, APPLICATION_JSON), assetKind);
        String response = getResponseJson(savedJson);

        return new Response.Builder()
                .status(success(OK))
                .payload(new StringPayload(response, APPLICATION_JSON))
                .build();
    }

    private String getResponseJson(final String savedJson) throws IOException {
        String response;
        try (ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(savedJson.getBytes())) {
            response = uploadUrlManager.prefixToValues(getRepository().getUrl(), byteArrayInputStream);
        }
        return response;
    }

    private String getSavedJson(final String assetPath, final Payload payload) throws IOException {
        String savedJson;
        try (InputStream inputStream = payload.openInputStream()) {
            savedJson = uploadUrlManager.convertKeys(assetPath + "/", inputStream);
        }
        return savedJson;
    }

    public Response upload(final String assetPath,
                           final ConanCoords coord,
                           final Payload payload,
                           final AssetKind assetKind) throws IOException {
        checkNotNull(assetPath);
        checkNotNull(coord);
        checkNotNull(payload);
        checkNotNull(assetKind);

        doPutArchive(assetPath, coord, payload, assetKind);

        return new Response.Builder()
                .status(success(OK))
                .build();
    }

    private void doPutArchive(final String assetPath,
                              final ConanCoords coord,
                              final Payload payload,
                              final AssetKind assetKind) throws IOException {
        StorageFacet storageFacet = facet(StorageFacet.class);
        try (TempBlob tempBlob = storageFacet.createTempBlob(payload, ConanFacetUtils.HASH_ALGORITHMS)) {
            doPutArchive(coord, assetPath, tempBlob, assetKind);
        }
    }

    @TransactionalStoreBlob
    protected void doPutArchive(final ConanCoords coord,
                                final String path,
                                final TempBlob tempBlob,
                                final AssetKind assetKind) throws IOException {
        checkNotNull(path);
        checkNotNull(tempBlob);

        StorageTx tx = UnitOfWork.currentTx();
        Bucket bucket = tx.findBucket(getRepository());

        Map<String, Object> attributes = new HashMap<>();
        attributes.put(GROUP, coord.getGroup());
        attributes.put(PROJECT, coord.getProject());
        attributes.put(VERSION, coord.getVersion());
        attributes.put(STATE, coord.getChannel());

        Component component = findComponent(tx, getRepository(), coord);
        if (component == null) {
            component = tx.createComponent(bucket, getRepository().getFormat())
                    .group(coord.getGroup())
                    .name(coord.getProject())
                    .version(coord.getVersion())
                    .attributes(new NestedAttributesMap("metadata", attributes));
        }
        tx.saveComponent(component);

        Asset asset = findAsset(tx, bucket, path);
        if (asset == null) {
            asset = tx.createAsset(bucket, component);
            asset.name(path);
            asset.formatAttributes().set(P_ASSET_KIND, assetKind);
        }

        saveAsset(tx, asset, tempBlob);
    }

    private Content saveAsset(final StorageTx tx,
                              final Asset asset,
                              final Supplier<InputStream> contentSupplier) throws IOException {
        return saveAsset(tx, asset, contentSupplier, null, null);
    }

    private Content saveAsset(final StorageTx tx,
                              final Asset asset,
                              final Supplier<InputStream> contentSupplier,
                              final String contentType,
                              final AttributesMap contentAttributes) throws IOException {
        Content.applyToAsset(asset, maintainLastModified(asset, contentAttributes));
        AssetBlob assetBlob = tx.setBlob(
                asset, asset.name(), contentSupplier, HASH_ALGORITHMS, null, contentType, false
        );

        asset.markAsDownloaded();
        tx.saveAsset(asset);
        return toContent(asset, assetBlob.getBlob());
    }

    /**
     * Services the download_urls endpoint for root and package data
     *
     * @param gavPath path as GAV
     * @param context
     * @return json response of conan files to lookup
     * @throws IOException
     */
    public Response getDownloadUrl(final String gavPath, final Context context) throws IOException {
        log.debug("Original request {} is fetching locally from {}", context.getRequest().getPath(), gavPath);

        Content content = doGet(gavPath);
        if (content == null) {
            return HttpResponses.notFound();
        }

        String response;
        try (InputStream inputStream = content.openInputStream()) {
            response = uploadUrlManager.prefixToValues(getRepository().getUrl(), inputStream);
        }

        return new Response.Builder()
                .status(success(OK))
                .payload(new StringPayload(response, APPLICATION_JSON))
                .build();
    }

    @TransactionalStoreBlob
    public Response search(final Context context) throws JsonProcessingException {
        Parameters parameters = context.getRequest().getParameters();
        log.info("[search] : {}", parameters);

        StorageTx tx = UnitOfWork.currentTx();

        String q = parameters.get("q").replace("*", "%");

        Pattern pattern = Pattern.compile("(?<name>[^/@]*)(/(?<version>[^/@]*))?((@(?<group>[^/@]*))?(/(?<channel>[^/@]*))?)?");

        Matcher matcher = pattern.matcher(q);
        if (!matcher.matches()) {
            return HttpResponses.notFound();
        }

        Query.Builder builder = Query.builder().where(P_NAME).like(matcher.group("name"));

        appendQueryComponent(builder, matcher, "version");
        appendQueryComponent(builder, matcher, "group");
        String channel = matcher.group("channel");
        Query query = builder.build();
        Iterable<Component> components = tx.findComponents(
                query,
                singletonList(getRepository())
        );

        Collection matches = new ArrayList<String>();
        components.forEach(c -> {
            log.info("[search] : {}", c);
            ConanCoords coord = ConanCoords.fromComponent(c);
            if (channel != null) {
                Object state = c.attributes().get(STATE);
                if (state != null && state.equals(channel)) {
                    matches.add(ConanCoords.getSpec(coord));
                }
            } else {
                matches.add(ConanCoords.getSpec(coord));
            }
        });

        HashMap<String, Object> result = new HashMap<>();
        result.put("results", matches);
        String resultString = MAPPER.writeValueAsString(result);
        return new Response.Builder()
                .status(success(OK))
                .payload(new StringPayload(resultString, APPLICATION_JSON))
                .build();
    }

    private void appendQueryComponent(Query.Builder builder, Matcher matcher, String group) {
        if (matcher.group(group) != null) {
            builder.and(group).like(matcher.group(group));
        }
    }

    @TransactionalStoreBlob
    public Response searchUrl(final Context context) throws JsonProcessingException {
        StorageTx tx = UnitOfWork.currentTx();
        TokenMatcher.State state = context.getAttributes().require(TokenMatcher.State.class);
        ConanCoords coord = ConanCoords.convertFromState(state);
        Component component = findComponent(tx, getRepository(), coord);

        if (component == null) {
            return HttpResponses.notFound("Recipe not found: " + ConanCoords.getSpec(coord));
        }

        Iterable<Asset> assets = tx.browseAssets(component);

        HashMap<String, Object> result = new HashMap<>();
        for (Asset asset : assets) {
            log.debug("[searchUrl]: {}", asset);
            String name = asset.name();
            if (name.endsWith("conaninfo.txt")) {
                String sha = name.substring(name.length() - 54, name.length() - 14);

                Blob blob = tx.requireBlob(asset.requireBlobRef());

                BufferedReader reader = new BufferedReader(new InputStreamReader(blob.getInputStream()));
                ConanInfo info = ConanInfo.load(reader);

                result.put(sha, info);
            }
        }

        String resultString = MAPPER.writeValueAsString(result);
        log.debug("[searchUrl] : {}", resultString);
        return new Response.Builder()
                .status(success(OK))
                .payload(new StringPayload(resultString, APPLICATION_JSON))
                .build();
    }

    public Response get(final Context context) {
        log.debug("Request {}", context.getRequest().getPath());

        Content content = doGet(context.getRequest().getPath());
        if (content == null) {
            return HttpResponses.notFound();
        }

        return new Response.Builder()
                .status(success(OK))
                .payload(new StreamPayload(
                        new InputStreamSupplier() {
                            @Nonnull
                            @Override
                            public InputStream get() throws IOException {
                                return content.openInputStream();
                            }
                        },
                        content.getSize(),
                        content.getContentType()))
                .build();
    }

    @Nullable
    @TransactionalStoreBlob
    protected Content doGet(final String path) {
        checkNotNull(path);

        StorageTx tx = UnitOfWork.currentTx();

        Asset asset = findAsset(tx, tx.findBucket(getRepository()), path);
        if (asset == null) {
            return null;
        }
        if (asset.markAsDownloaded()) {
            tx.saveAsset(asset);
        }
        return toContent(asset, tx.requireBlob(asset.requireBlobRef()));
    }
}
