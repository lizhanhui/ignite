/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.spi.discovery.tcp.ipfinder.s3;

import com.amazonaws.AmazonClientException;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import java.io.ByteArrayInputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.LinkedList;
import java.util.StringTokenizer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.internal.IgniteInterruptedCheckedException;
import org.apache.ignite.internal.util.tostring.GridToStringExclude;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.apache.ignite.internal.util.typedef.internal.SB;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.resources.LoggerResource;
import org.apache.ignite.spi.IgniteSpiConfiguration;
import org.apache.ignite.spi.IgniteSpiException;
import org.apache.ignite.spi.discovery.tcp.ipfinder.TcpDiscoveryIpFinderAdapter;

/**
 * AWS S3-based IP finder.
 * <p>
 * For information about Amazon S3 visit <a href="http://aws.amazon.com">aws.amazon.com</a>.
 * <h1 class="header">Configuration</h1>
 * <h2 class="header">Mandatory</h2>
 * <ul>
 *      <li>AWS credentials (see {@link #setAwsCredentials(AWSCredentials)} and
 *      {@link #setAwsCredentialsProvider(AWSCredentialsProvider)}</li>
 *      <li>Bucket name (see {@link #setBucketName(String)})</li>
 * </ul>
 * <h2 class="header">Optional</h2>
 * <ul>
 *      <li>Client configuration (see {@link #setClientConfiguration(ClientConfiguration)})</li>
 *      <li>Shared flag (see {@link #setShared(boolean)})</li>
 * </ul>
 * <p>
 * The finder will create S3 bucket with configured name. The bucket will contain entries named
 * like the following: {@code 192.168.1.136#1001}.
 * <p>
 * Note that storing data in AWS S3 service will result in charges to your AWS account.
 * Choose another implementation of {@link org.apache.ignite.spi.discovery.tcp.ipfinder.TcpDiscoveryIpFinder} for local
 * or home network tests.
 * <p>
 * Note that this finder is shared by default (see {@link org.apache.ignite.spi.discovery.tcp.ipfinder.TcpDiscoveryIpFinder#isShared()}.
 */
public class TcpDiscoveryS3IpFinder extends TcpDiscoveryIpFinderAdapter {
    /** Delimiter to use in S3 entries name. */
    public static final String DELIM = "#";

    /** Entry content. */
    private static final byte[] ENTRY_CONTENT = new byte[] {1};

    /** Entry metadata with content length set. */
    private static final ObjectMetadata ENTRY_METADATA;

    static {
        ENTRY_METADATA = new ObjectMetadata();

        ENTRY_METADATA.setContentLength(ENTRY_CONTENT.length);
    }

    /** Grid logger. */
    @LoggerResource
    private IgniteLogger log;

    /** Client to interact with S3 storage. */
    @GridToStringExclude
    private AmazonS3 s3;

    /** Bucket name. */
    private String bucketName;

    private String prefix;

    /** Init guard. */
    @GridToStringExclude
    private final AtomicBoolean initGuard = new AtomicBoolean();

    /** Init latch. */
    @GridToStringExclude
    private final CountDownLatch initLatch = new CountDownLatch(1);

    /** Amazon client configuration. */
    private ClientConfiguration cfg;

    private Regions region;

    /** AWS Credentials. */
    @GridToStringExclude
    private AWSCredentials cred;

    /** AWS Credentials. */
    @GridToStringExclude
    private AWSCredentialsProvider credProvider;

    /**
     * Constructor.
     */
    public TcpDiscoveryS3IpFinder() {
        setShared(true);
    }

    /** {@inheritDoc} */
    @Override public Collection<InetSocketAddress> getRegisteredAddresses() throws IgniteSpiException {
        initClient();

        Collection<InetSocketAddress> addrs = new LinkedList<>();

        try {
            ObjectListing list;

            if (isNullOrEmpty(prefix)) {
                list = s3.listObjects(bucketName);
            } else {
                list = s3.listObjects(bucketName, prefix);
            }

            while (true) {
                for (S3ObjectSummary sum : list.getObjectSummaries()) {
                    String key = sum.getKey();
                    if (!isNullOrEmpty(prefix)) {
                        key = key.substring(prefix.length() + 1);
                    }
                    StringTokenizer st = new StringTokenizer(key, DELIM);

                    if (st.countTokens() != 2)
                        U.error(log, "Failed to parse S3 entry due to invalid format: " + key);
                    else {
                        String addrStr = st.nextToken();
                        String portStr = st.nextToken();

                        int port = -1;

                        try {
                            port = Integer.parseInt(portStr);
                        }
                        catch (NumberFormatException e) {
                            U.error(log, "Failed to parse port for S3 entry: " + key, e);
                        }

                        if (port != -1)
                            try {
                                addrs.add(new InetSocketAddress(addrStr, port));
                            }
                            catch (IllegalArgumentException e) {
                                U.error(log, "Failed to parse port for S3 entry: " + key, e);
                            }
                    }
                }

                if (list.isTruncated()) {
                    list = s3.listNextBatchOfObjects(list);
                } else {
                    break;
                }
            }
        }
        catch (AmazonClientException e) {
            throw new IgniteSpiException("Failed to list objects in the bucket: " + bucketName, e);
        }

        return addrs;
    }

    /** {@inheritDoc} */
    @Override public void registerAddresses(Collection<InetSocketAddress> addrs) throws IgniteSpiException {
        assert !F.isEmpty(addrs);

        initClient();

        for (InetSocketAddress addr : addrs) {

            InetAddress inetAddress = addr.getAddress();
            if (inetAddress.isAnyLocalAddress() || inetAddress.isLoopbackAddress() || inetAddress instanceof Inet6Address) {
                continue;
            }

            String key = key(addr);

            try {
                s3.putObject(bucketName, key, new ByteArrayInputStream(ENTRY_CONTENT), ENTRY_METADATA);
            }
            catch (AmazonClientException e) {
                throw new IgniteSpiException("Failed to put entry [bucketName=" + bucketName +
                    ", entry=" + key + ']', e);
            }
        }
    }

    /** {@inheritDoc} */
    @Override public void unregisterAddresses(Collection<InetSocketAddress> addrs) throws IgniteSpiException {
        assert !F.isEmpty(addrs);

        initClient();

        for (InetSocketAddress addr : addrs) {
            String key = key(addr);

            try {
                s3.deleteObject(bucketName, key);
            }
            catch (AmazonClientException e) {
                throw new IgniteSpiException("Failed to delete entry [bucketName=" + bucketName +
                    ", entry=" + key + ']', e);
            }
        }
    }

    /**
     * Gets S3 key for provided address.
     *
     * @param addr Node address.
     * @return Key.
     */
    private String key(InetSocketAddress addr) {
        assert addr != null;

        SB sb = new SB();

        if (!isNullOrEmpty(prefix)) {
            sb.a(prefix).a("/");
        }

        sb.a(addr.getAddress().getHostAddress())
            .a(DELIM)
            .a(addr.getPort());

        return sb.toString();
    }

    /**
     * Amazon s3 client initialization.
     *
     * @throws IgniteSpiException In case of error.
     */
    @SuppressWarnings({"BusyWait"})
    private void initClient() throws IgniteSpiException {
        if (initGuard.compareAndSet(false, true))
            try {
                if (cred == null && credProvider == null)
                    throw new IgniteSpiException("AWS credentials are not set.");

                if (cfg == null)
                    U.warn(log, "Amazon client configuration is not set (will use default).");

                if (F.isEmpty(bucketName))
                    throw new IgniteSpiException("Bucket name is null or empty (provide bucket name and restart).");

                s3 = createAmazonS3Client();

                if (!s3.doesBucketExist(bucketName)) {
                    try {
                        s3.createBucket(bucketName);

                        if (log.isDebugEnabled())
                            log.debug("Created S3 bucket: " + bucketName);

                        while (!s3.doesBucketExist(bucketName))
                            try {
                                U.sleep(200);
                            }
                            catch (IgniteInterruptedCheckedException e) {
                                throw new IgniteSpiException("Thread has been interrupted.", e);
                            }
                    }
                    catch (AmazonClientException e) {
                        if (!s3.doesBucketExist(bucketName)) {
                            s3 = null;

                            throw new IgniteSpiException("Failed to create bucket: " + bucketName, e);
                        }
                    }
                }
            }
            finally {
                initLatch.countDown();
            }
        else {
            try {
                U.await(initLatch);
            }
            catch (IgniteInterruptedCheckedException e) {
                throw new IgniteSpiException("Thread has been interrupted.", e);
            }

            if (s3 == null)
                throw new IgniteSpiException("Ip finder has not been initialized properly.");
        }
    }

    /**
     * Instantiates {@code AmazonS3Client} instance.
     *
     * @return Client instance to use to connect to AWS.
     */
    private AmazonS3 createAmazonS3Client() {
        AmazonS3ClientBuilder s3ClientBuilder = AmazonS3ClientBuilder.standard();

        if (null != cred) {
            s3ClientBuilder.withCredentials(new AWSStaticCredentialsProvider(cred));
        }

        if (null != credProvider) {
            s3ClientBuilder.withCredentials(credProvider);
        }

        if (null != cfg) {
            s3ClientBuilder.withClientConfiguration(cfg);
        }

        if (null != region) {
            s3ClientBuilder.withRegion(region);
        }

        return s3ClientBuilder.build();
    }

    /**
     * Sets bucket name for IP finder.
     *
     * @param bucketName Bucket name.
     * @return {@code this} for chaining.
     */
    @IgniteSpiConfiguration(optional = false)
    public TcpDiscoveryS3IpFinder setBucketName(String bucketName) {
        this.bucketName = bucketName;

        return this;
    }

    /**
     * Sets Amazon client configuration.
     * <p>
     * For details refer to Amazon S3 API reference.
     *
     * @param cfg Amazon client configuration.
     * @return {@code this} for chaining.
     */
    @IgniteSpiConfiguration(optional = true)
    public TcpDiscoveryS3IpFinder setClientConfiguration(ClientConfiguration cfg) {
        this.cfg = cfg;

        return this;
    }

    /**
     * <p>
     *  Set Amazon client region.
     * </p>
     *
     * @param region Region of the AWS S3.
     * @return {@code this} for chaining.
     */
    @IgniteSpiConfiguration(optional = true)
    public TcpDiscoveryS3IpFinder setRegion(Regions region) {
        this.region = region;
        return this;
    }

    /**
     * Set prefix to use as sub-directory.
     * @param prefix bucket directory.
     * @return {@code this} for chaining.
     */
    @IgniteSpiConfiguration(optional = true)
    public TcpDiscoveryS3IpFinder setPrefix(String prefix) {
        if (null != prefix && prefix.endsWith("/")) {
            throw new IllegalArgumentException("Please remove trailing '/'");
        }
        this.prefix = prefix;
        return this;
    }

    /**
     * Sets AWS credentials. Either use {@link #setAwsCredentialsProvider(AWSCredentialsProvider)} or this one.
     * <p>
     * For details refer to Amazon S3 API reference.
     *
     * @param cred AWS credentials.
     * @return {@code this} for chaining.
     */
    @IgniteSpiConfiguration(optional = false)
    public TcpDiscoveryS3IpFinder setAwsCredentials(AWSCredentials cred) {
        this.cred = cred;

        return this;
    }

    /**
     * Sets AWS credentials provider. Either use {@link #setAwsCredentials(AWSCredentials)} or this one.
     * <p>
     * For details refer to Amazon S3 API reference.
     *
     * @param credProvider AWS credentials provider.
     * @return {@code this} for chaining.
     */
    @IgniteSpiConfiguration(optional = false)
    public TcpDiscoveryS3IpFinder setAwsCredentialsProvider(AWSCredentialsProvider credProvider) {
        this.credProvider = credProvider;

        return this;
    }

    /** {@inheritDoc} */
    @Override public TcpDiscoveryS3IpFinder setShared(boolean shared) {
        super.setShared(shared);
        return this;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(TcpDiscoveryS3IpFinder.class, this, "super", super.toString());
    }

    private static boolean isNullOrEmpty(String s) {
        return null == s || s.trim().isEmpty();
    }
}