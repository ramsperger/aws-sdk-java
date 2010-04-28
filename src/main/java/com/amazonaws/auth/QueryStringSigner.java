/*
 * Copyright 2010 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.amazonaws.auth;

import java.net.URI;
import java.security.SignatureException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.codec.binary.Base64;

import com.amazonaws.Request;
import com.amazonaws.util.HttpUtils;

/**
 * Signer implementation responsible for signing an AWS query string request
 * according to the various signature versions and hashing algorithms.
 */
public class QueryStringSigner<T> implements Signer<T> {

    /** The default encoding to use when URL encoding */
    private static final String DEFAULT_ENCODING = "UTF-8";

    /**
     * AWS Credentials
     */
    private final AWSCredentials credentials;

    /**
     * Constructs a new QueryStringSigner to sign requests based on the
     * specified service endpoint (ex: "s3.amazonaws.com") and AWS secret access
     * key.
     *
     * @param credentials
     *            AWS Credentials
     */
    public QueryStringSigner(AWSCredentials credentials) {
        this.credentials = credentials;
    }

    /**
     * This signer will add "Signature" parameter to the request. Default
     * signature version is "2" and default signing algorithm is "HmacSHA256".
     *
     * AWSAccessKeyId SignatureVersion SignatureMethod Timestamp Signature
     *
     * @param request
     *            request to be signed.
     *
     * @throws SignatureException
     */
    public void sign(Request<T> request) throws SignatureException {
        sign(request, SignatureVersion.V2, SigningAlgorithm.HmacSHA256);
    }

    /**
     * This signer will add following authentication parameters to the request:
     *
     * AWSAccessKeyId SignatureVersion SignatureMethod Timestamp Signature
     *
     * @param request
     *            request to be signed.
     *
     * @param version
     *            signature version. "2" is recommended.
     *
     * @param algorithm
     *            signature algorithm. "HmacSHA256" is recommended.
     *
     * @throws SignatureException
     */
    public void sign(Request<T> request, SignatureVersion version,
            SigningAlgorithm algorithm) throws SignatureException {
        request.addParameter("AWSAccessKeyId", credentials.getAWSAccessKeyId());
        request.addParameter("SignatureVersion", version.toString());
        request.addParameter("Timestamp", getFormattedTimestamp());

        String stringToSign = null;
        switch (version) {
        case V1:
            stringToSign = calculateStringToSignV1(request.getParameters());
            break;

        case V2:
            request.addParameter("SignatureMethod", algorithm.toString());
            stringToSign = calculateStringToSignV2(request.getEndpoint(),
                    request.getParameters());
            break;

        default:
            throw new SignatureException("Invalid Signature Version specified");
        }

        String signatureValue = sign(stringToSign, credentials
                .getAWSSecretKey(), algorithm);
        request.addParameter("Signature", signatureValue);
    }

    /**
     * Computes RFC 2104-compliant HMAC signature.
     */
    private String sign(String data, String key, SigningAlgorithm algorithm)
            throws SignatureException {
        try {
            Mac mac = Mac.getInstance(algorithm.toString());
            mac.init(new SecretKeySpec(key.getBytes(), algorithm.toString()));
            byte[] signature = Base64.encodeBase64(mac.doFinal(data
                    .getBytes(DEFAULT_ENCODING)));
            return new String(signature);
        } catch (Exception e) {
            throw new SignatureException("Failed to generate signature: "
                    + e.getMessage(), e);
        }
    }

    /**
     * Calculates string to sign for signature version 1.
     *
     * @param parameters
     *            request parameters
     *
     * @return String to sign
     */
    private String calculateStringToSignV1(Map<String, String> parameters) {
        StringBuilder data = new StringBuilder();
        Map<String, String> sorted = new TreeMap<String, String>(
                String.CASE_INSENSITIVE_ORDER);
        sorted.putAll(parameters);

        for (String key : sorted.keySet()) {
            data.append(key);
            data.append(sorted.get(key));
        }

        return data.toString();
    }

    /**
     * Calculate string to sign for signature version 2.
     *
     * @param parameters
     *            request parameters
     *
     * @param serviceUrl
     *            service url
     *
     * @return String to sign
     *
     * @throws SignatureException
     *             If the string to sign cannot be calculated.
     */
    private String calculateStringToSignV2(URI endpoint,
            Map<String, String> parameters) throws SignatureException {
        StringBuilder data = new StringBuilder();
        data.append("POST");
        data.append("\n");
        data.append(endpoint.getHost().toLowerCase());
        /*
         * Apache HttpClient will omit the port in the Host header for default
         * port values (i.e. 80 for HTTP and 443 for HTTPS) even if we
         * explicitly specify it, so we need to be careful that we use the same
         * value here when we calculate the string to sign and in the Host
         * header we send in the HTTP request.
         */
        if (HttpUtils.isUsingNonDefaultPort(endpoint)) {
            data.append(":" + endpoint.getPort());
        }
        data.append("\n");
        String uri = endpoint.getPath();
        if (uri == null || uri.length() == 0) {
            uri = "/";
        }
        data.append(HttpUtils.urlEncode(uri, true));
        data.append("\n");
        Map<String, String> sorted = new TreeMap<String, String>();
        sorted.putAll(parameters);

        Iterator<Map.Entry<String, String>> pairs = sorted.entrySet()
                .iterator();
        while (pairs.hasNext()) {
            Map.Entry<String, String> pair = pairs.next();
            String key = pair.getKey();
            String value = pair.getValue();
            data.append(HttpUtils.urlEncode(key, false));
            data.append("=");
            data.append(HttpUtils.urlEncode(value, false));
            if (pairs.hasNext()) {
                data.append("&");
            }
        }
        return data.toString();
    }

    /**
     * Formats date as ISO 8601 timestamp
     */
    private String getFormattedTimestamp() {
        SimpleDateFormat df = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        df.setTimeZone(TimeZone.getTimeZone("UTC"));
        return df.format(new Date());
    }

}
