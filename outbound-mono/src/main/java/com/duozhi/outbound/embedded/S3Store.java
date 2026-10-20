package com.duozhi.outbound.embedded;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
/** Locked AWS SDK v2 adapter with the source path-style, no-retry and verified-recording contract. */
final class S3Store implements AutoCloseable {
    private final Settings settings;private final S3Client client;private final String bucket,region;
    S3Store(Settings settings){
        this.settings=settings;URI endpoint=Support.endpoint(settings.get("S3_ENDPOINT_URL","http://seaweedfs:8333"));if(endpoint.getQuery()!=null)throw new IllegalArgumentException("S3 endpoint must not have a query");bucket=settings.get("S3_BUCKET","ai-outbound-recordings");if(!bucket.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,62}"))throw new IllegalArgumentException("invalid S3 bucket");region=settings.get("S3_REGION","us-east-1");String access=settings.secret("S3_ACCESS_KEY_ID"),secret=settings.secret("S3_SECRET_ACCESS_KEY");if(access.isEmpty()||secret.isEmpty())throw new IllegalArgumentException("S3 credentials are required");Duration timeout=Duration.ofSeconds(settings.number("RECORDING_DOWNLOAD_TIMEOUT_SEC",60,1,3600));
        client=S3Client.builder().endpointOverride(endpoint).region(Region.of(region)).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access,secret))).serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).checksumValidationEnabled(false).build()).httpClientBuilder(ApacheHttpClient.builder().connectionTimeout(Duration.ofSeconds(8)).socketTimeout(timeout).proxyConfiguration(ProxyConfiguration.builder().useSystemPropertyValues(false).useEnvironmentVariableValues(false).build())).overrideConfiguration(ClientOverrideConfiguration.builder().retryPolicy(RetryPolicy.none()).apiCallTimeout(timeout).apiCallAttemptTimeout(timeout).build()).build();
    }
    String bucket(){return bucket;}
    static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20").replace("%7E","~");}
    boolean ready(){try{return head()==200;}catch(Exception error){return false;}}
    int head()throws IOException,InterruptedException{try{return client.headBucket(HeadBucketRequest.builder().bucket(bucket).build()).sdkHttpResponse().statusCode();}catch(S3Exception error){return error.statusCode();}catch(SdkClientException error){throw transport(error);}}
    void initialize()throws IOException,InterruptedException{int status=head();if(status==200)return;if(status!=404||!settings.flag("S3_AUTO_CREATE_BUCKET",true))throw new Support.Failure(503,"unable to access recording bucket");try{var request=CreateBucketRequest.builder().bucket(bucket);if(!region.equals("us-east-1"))request.createBucketConfiguration(CreateBucketConfiguration.builder().locationConstraint(region).build());client.createBucket(request.build());}catch(S3Exception error){throw new Support.Failure(503,"unable to create recording bucket");}catch(SdkClientException error){throw transport(error);}}
    void upload(String key,Path file,String type,Map<String,String> metadata)throws IOException,InterruptedException{try{client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(type).metadata(metadata).build(),RequestBody.fromFile(file));}catch(S3Exception error){String code=error.awsErrorDetails()==null?"":Objects.toString(error.awsErrorDetails().errorCode(),"");throw new Support.Failure(503,"recording upload failed (HTTP "+error.statusCode()+(code.matches("[A-Za-z0-9]{1,100}")?", "+code:"")+")");}catch(SdkClientException error){throw transport(error);}}
    void delete(String key)throws IOException,InterruptedException{try{client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());}catch(S3Exception error){throw new Support.Failure(503,"recording deletion failed");}catch(SdkClientException error){throw transport(error);}}
    InputStream download(String key)throws IOException,InterruptedException{try{var response=client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());if(response.response().sdkHttpResponse().statusCode()!=200){response.close();throw new Support.Failure(503,"recording verification failed");}return Support.timed(response,settings.number("RECORDING_DOWNLOAD_TIMEOUT_SEC",60,1,3600)*1000);}catch(S3Exception error){throw new Support.Failure(503,"recording verification failed");}catch(SdkClientException error){throw transport(error);}}
    private IOException transport(SdkClientException error)throws InterruptedException{if(Thread.currentThread().isInterrupted())throw new InterruptedException("S3 request interrupted");return new IOException("S3 transport failed",error);}
    @Override public void close(){client.close();}
}
