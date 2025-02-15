/*
 * Copyright (C)2016 - SMBJ Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hierynomus.smbj.auth;

import com.hierynomus.asn1.types.primitive.ASN1ObjectIdentifier;
import com.hierynomus.ntlm.functions.NtlmFunctions;
import com.hierynomus.ntlm.messages.*;
import com.hierynomus.protocol.commons.ByteArrayUtils;
import com.hierynomus.protocol.commons.EnumWithValue;
import com.hierynomus.protocol.commons.buffer.Buffer;
import com.hierynomus.protocol.commons.buffer.Endian;
import com.hierynomus.security.SecurityProvider;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.common.SMBRuntimeException;
import com.hierynomus.smbj.connection.ConnectionContext;
import com.hierynomus.spnego.NegTokenInit;
import com.hierynomus.spnego.NegTokenTarg;
import com.hierynomus.spnego.SpnegoException;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Random;

import static com.hierynomus.ntlm.messages.NtlmNegotiateFlag.*;

public class NtlmAuthenticator implements Authenticator {

    // The OID for NTLMSSP
    private static final ASN1ObjectIdentifier NTLMSSP = new ASN1ObjectIdentifier("1.3.6.1.4.1.311.2.2.10");
    private SecurityProvider securityProvider;
    private Random random;
    private String workStationName;

    public static class Factory implements com.hierynomus.protocol.commons.Factory.Named<Authenticator> {
        @Override
        public String getName() {
            return NTLMSSP.getValue();
        }

        @Override
        public NtlmAuthenticator create() {
            return new NtlmAuthenticator();
        }
    }

    private boolean initialized = false;
    private boolean completed = false;

    @Override
    public AuthenticateResponse authenticate(final AuthenticationContext context, final byte[] gssToken, ConnectionContext connectionContext) throws IOException {
        try {
            AuthenticateResponse response = new AuthenticateResponse();
            if (completed) {
                return null;
            } else if (!initialized) {
                System.out.println("tempGT2: Initialized Authentication of " + context.getUsername() + " using NTLM");
                NtlmNegotiate ntlmNegotiate = new NtlmNegotiate();
                initialized = true;
                response.setNegToken(negTokenInit(ntlmNegotiate));
                return response;
            } else {
                System.out.println("tempGT2: Received token: " + ByteArrayUtils.printHex(gssToken));
                NtlmFunctions ntlmFunctions = new NtlmFunctions(random, securityProvider);
                NegTokenTarg negTokenTarg = new NegTokenTarg().read(gssToken);
                BigInteger negotiationResult = negTokenTarg.getNegotiationResult();
                NtlmChallenge serverNtlmChallenge = new NtlmChallenge();
                try {
                    serverNtlmChallenge.read(new Buffer.PlainBuffer(negTokenTarg.getResponseToken(), Endian.LE));
                } catch (Buffer.BufferException e) {
                    throw new IOException(e);
                }
                System.out.println("tempGT2: Received NTLM challenge from: " + serverNtlmChallenge.getTargetName());

                response.setWindowsVersion(serverNtlmChallenge.getVersion());
                response.setNetBiosName(serverNtlmChallenge.getTargetInfo().getAvPairString(AvId.MsvAvNbComputerName));

                byte[] serverChallenge = serverNtlmChallenge.getServerChallenge();
                byte[] responseKeyNT = ntlmFunctions.NTOWFv2(String.valueOf(context.getPassword()), context.getUsername(), context.getDomain());
                TargetInfo clientTargetInfo = serverNtlmChallenge.getTargetInfo().copy();
                EnumSet<NtlmNegotiateFlag> negotiateFlags = serverNtlmChallenge.getNegotiateFlags();
                if (negotiateFlags.contains(NTLMSSP_REQUEST_TARGET)) {
                    clientTargetInfo.putAvPairString(AvId.MsvAvTargetName, String.format("cifs/%s", clientTargetInfo.getAvPairString(AvId.MsvAvDnsComputerName)));
                }

                byte[] ntlmv2ClientChallenge = ntlmFunctions.getNTLMv2ClientChallenge(clientTargetInfo);
                byte[] ntlmv2Response = ntlmFunctions.getNTLMv2Response(responseKeyNT, serverChallenge, ntlmv2ClientChallenge);
                byte[] sessionkey;

                byte[] userSessionKey = ntlmFunctions.hmac_md5(responseKeyNT, Arrays.copyOfRange(ntlmv2Response, 0, 16)); // first 16 bytes of ntlmv2Response is ntProofStr
                if (negotiateFlags.contains(NTLMSSP_NEGOTIATE_KEY_EXCH)
                    && (negotiateFlags.contains(NTLMSSP_NEGOTIATE_SIGN)
                    || negotiateFlags.contains(NTLMSSP_NEGOTIATE_SEAL)
                    || negotiateFlags.contains(NTLMSSP_NEGOTIATE_ALWAYS_SIGN))
                    ) {
                    byte[] masterKey = new byte[16];
                    random.nextBytes(masterKey);
                    sessionkey = ntlmFunctions.encryptRc4(userSessionKey, masterKey);
                    response.setSessionKey(masterKey);
                } else {
                    sessionkey = userSessionKey;
                    response.setSessionKey(sessionkey);
                }

                completed = true;

                // If NTLM v2 is used, KeyExchangeKey MUST be set to the given 128-bit SessionBaseKey value.

                // MIC (16 bytes) provided if in AvPairType is key MsvAvFlags with value & 0x00000002 is true
                Object msAvTimestamp = serverNtlmChallenge.getTargetInfo().getAvPairObject(AvId.MsvAvTimestamp);
                if (msAvTimestamp != null) {
                    // MIC should be calculated
                    NtlmAuthenticate resp = new NtlmAuthenticate(new byte[0], ntlmv2Response,
                        context.getUsername(), context.getDomain(), workStationName, sessionkey, EnumWithValue.EnumUtils.toLong(negotiateFlags),
                        true
                    );

                    // TODO correct hash should be tested

                    Buffer.PlainBuffer concatenatedBuffer = new Buffer.PlainBuffer(Endian.LE);
                    concatenatedBuffer.putRawBytes(negTokenTarg.getResponseToken()); //negotiateMessage
                    concatenatedBuffer.putRawBytes(serverNtlmChallenge.getServerChallenge()); //challengeMessage
                    resp.writeAutentificateMessage(concatenatedBuffer); //authentificateMessage

                    byte[] mic = ntlmFunctions.hmac_md5(userSessionKey, concatenatedBuffer.getCompactData());
                    resp.setMic(mic);
                    response.setNegToken(negTokenTarg(resp));
                    return response;
                } else {
                    NtlmAuthenticate resp = new NtlmAuthenticate(new byte[0], ntlmv2Response,
                        context.getUsername(), context.getDomain(), workStationName, sessionkey, EnumWithValue.EnumUtils.toLong(negotiateFlags),
                        false
                    );
                    response.setNegToken(negTokenTarg(resp));
                    return response;
                }
            }
        } catch (SpnegoException spne) {
            throw new SMBRuntimeException(spne);
        }
    }

    private byte[] negTokenInit(NtlmNegotiate ntlmNegotiate) throws SpnegoException {
        NegTokenInit negTokenInit = new NegTokenInit();
        negTokenInit.addSupportedMech(NTLMSSP);
        Buffer.PlainBuffer ntlmBuffer = new Buffer.PlainBuffer(Endian.LE);
        ntlmNegotiate.write(ntlmBuffer);
        negTokenInit.setMechToken(ntlmBuffer.getCompactData());
        Buffer.PlainBuffer negTokenBuffer = new Buffer.PlainBuffer(Endian.LE);
        negTokenInit.write(negTokenBuffer);
        return negTokenBuffer.getCompactData();
    }

    private byte[] negTokenTarg(NtlmAuthenticate resp) throws SpnegoException {
        NegTokenTarg targ = new NegTokenTarg();
        Buffer.PlainBuffer ntlmBuffer = new Buffer.PlainBuffer(Endian.LE);
        resp.write(ntlmBuffer);
        targ.setResponseToken(ntlmBuffer.getCompactData());
        Buffer.PlainBuffer negTokenBuffer = new Buffer.PlainBuffer(Endian.LE);
        targ.write(negTokenBuffer);
        return negTokenBuffer.getCompactData();
    }

    @Override
    public void init(SmbConfig config) {
        this.securityProvider = config.getSecurityProvider();
        this.random = config.getRandomProvider();
        this.workStationName = config.getWorkStationName();
    }

    @Override
    public boolean supports(AuthenticationContext context) {
        return context.getClass().equals(AuthenticationContext.class);
    }

}
