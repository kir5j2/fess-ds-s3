/*
 * Copyright 2012-2019 CodeLibs Project and the Others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.codelibs.fess.ds.s3;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import org.codelibs.core.lang.StringUtil;
import org.codelibs.fess.exception.DataStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;


public class AmazonS3Client implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(AmazonS3Client.class);

    // parameters for authentication
    protected static final String REGION = "region";
    protected static final String ACCESS_KEY_ID = "access_key_id";
    protected static final String SECRET_KEY = "secret_key";
    protected static final String ENDPOINT = "endpoint";
    protected static final String PROXY_HOST_PARAM = "proxy_host";
    protected static final String PROXY_PORT_PARAM = "proxy_port";

    // other parameters
    protected static final String MAX_CACHED_CONTENT_SIZE = "max_cached_content_size";
    protected static final String CUSTOM_VAR_TARGET_BUCKET_NAME = "custom_var_target_bucket_name";
    protected static final String CUSTOM_VAR_TARGET_PREFIX = "custom_var_target_prefix";

    protected final Map<String, String> params;

    protected final S3Client client;
    protected final Region region;
    protected final String endpoint;
    protected final String custom_var_target_bucket_name;
    protected final String custom_var_target_prefix;
    protected int maxCachedContentSize = 1024 * 1024;

    public AmazonS3Client(final Map<String, String> params) {
        this.params = params;
        final String size = params.get(MAX_CACHED_CONTENT_SIZE);
        if (StringUtil.isNotBlank(size)) {
            maxCachedContentSize = Integer.parseInt(size);
        }

        // CUSTOM_VAR_TARGET_BUCKET_NAME
        final String custom_var_target_bucket_name = params.getOrDefault(CUSTOM_VAR_TARGET_BUCKET_NAME, StringUtil.EMPTY);
        if (custom_var_target_bucket_name.isEmpty()) {
            throw new DataStoreException("Parameter '" + CUSTOM_VAR_TARGET_BUCKET_NAME + "' is required");
        }
        this.custom_var_target_bucket_name = params.get(CUSTOM_VAR_TARGET_BUCKET_NAME);

        // CUSTOM_VAR_TARGET_PREFIX
        final String custom_var_target_prefix = params.getOrDefault(CUSTOM_VAR_TARGET_PREFIX, StringUtil.EMPTY);
        if (custom_var_target_prefix.isEmpty()) {
            throw new DataStoreException("Parameter '" + CUSTOM_VAR_TARGET_PREFIX + "' is required");
        }
        this.custom_var_target_prefix = params.get(CUSTOM_VAR_TARGET_PREFIX);

        final String region = params.getOrDefault(REGION, StringUtil.EMPTY);
        if (region.isEmpty()) {
            throw new DataStoreException("Parameter '" + REGION + "' is required");
        }
        this.region = Region.of(region);
        this.endpoint = params.get(ENDPOINT);
        final String httpProxyHost = params.getOrDefault(PROXY_HOST_PARAM, StringUtil.EMPTY);
        final String httpProxyPort = params.getOrDefault(PROXY_PORT_PARAM, StringUtil.EMPTY);
        final AwsCredentialsProvider awsCredentialsProvider = new AwsBasicCredentialsProvider(params);
        try {
            final ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder();

            if (!httpProxyHost.isEmpty() ) {
                if (httpProxyPort.isEmpty()) {
                    throw new DataStoreException(PROXY_PORT_PARAM + " required.");
                }
                try {
                    httpClientBuilder.proxyConfiguration(ProxyConfiguration.builder()
                        .useSystemPropertyValues(true).endpoint(URI.create(httpProxyHost + ":" + Integer.parseInt(httpProxyPort))).build());
                } catch (final NumberFormatException e) {
                    throw new DataStoreException("parameter " + "'" + PROXY_PORT_PARAM + "' invalid.", e);
                }
            }

            final S3ClientBuilder builder = S3Client.builder() //
                    .region(this.region) //
                    .httpClient(httpClientBuilder.build()) //
                    .credentialsProvider(awsCredentialsProvider);
            if (Objects.nonNull(this.endpoint)) {
                builder.endpointOverride(URI.create(this.endpoint));
            }
            client = builder.build();
        } catch (final Exception e) {
            throw new DataStoreException("Failed to create a client.", e);
        }
    }
    
    public Region getRegion() {
        return region;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getCUSTOM_VAR_TARGET_BUCKET_NAME() {
        return custom_var_target_bucket_name;
    }

    public String getCUSTOM_VAR_TARGET_PREFIX() {
        return custom_var_target_prefix;
    }

    public void getBuckets(final Consumer<Bucket> consumer) {
        client.listBuckets().buckets().stream().filter(bkt -> bkt.name().contains(this.custom_var_target_bucket_name)).forEach(consumer);
    }

    public void getObjects(final String bucket, final Consumer<S3Object> consumer) {
        getObjects(bucket, 1000, consumer);
    }

    public void getObjects(final String bucket, final int maxKeys, final Consumer<S3Object> consumer) {
        ListObjectsV2Response response = client.listObjectsV2(builder -> builder.bucket(bucket).prefix(this.custom_var_target_prefix).fetchOwner(true).maxKeys(maxKeys).build());
        do {
            response.contents().forEach(consumer);
            final String next = response.nextContinuationToken();
            logger.info("**************** next: {} ****************", next);
            response = client.listObjectsV2(builder -> builder.bucket(bucket).prefix(this.custom_var_target_prefix).fetchOwner(true).maxKeys(maxKeys).continuationToken(next).build());
        } while(response.isTruncated());
    }

    public ResponseInputStream<GetObjectResponse> getObject(final String bucket, final String key) {
        return client.getObject(builder -> builder.bucket(bucket).key(key).build());
    }

    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
    }

    static class AwsBasicCredentialsProvider implements AwsCredentialsProvider {
        final String accessKeyId;
        final String secretAccessKey;

        AwsBasicCredentialsProvider(final Map<String, String> params) {
            accessKeyId = params.getOrDefault(ACCESS_KEY_ID, StringUtil.EMPTY);
            secretAccessKey = params.getOrDefault(SECRET_KEY, StringUtil.EMPTY);
            if (accessKeyId.isEmpty() || secretAccessKey.isEmpty()) {
                throw new DataStoreException("Parameter '" + //
                        ACCESS_KEY_ID + "', '" + //
                        SECRET_KEY + "' is required");
            }
        }

        @Override
        public AwsCredentials resolveCredentials() {
            return AwsBasicCredentials.create(accessKeyId, secretAccessKey);
        }
    }
}
