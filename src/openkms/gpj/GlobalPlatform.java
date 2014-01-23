/*
 * gpj - Global Platform for Java SmartCardIO
 *
 * Copyright (C) 2009 Wojciech Mostowski, woj@cs.ru.nl
 * Copyright (C) 2009 Francois Kooman, F.Kooman@student.science.ru.nl
 * Copyright (C) 2014 Martin Paljak, martin@martinpaljak.net
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 *
 */

package openkms.gpj;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import openkms.gpj.KeySet.KeyDiversification;
import openkms.gpj.KeySet.KeyType;


/**
 * The main Global Platform Service class. Provides most of the Global Platform
 * functionality for managing GP compliant smart cards.
 */
public class GlobalPlatform {

	public static final int SCP_ANY = 0;
	public static final int SCP_01_05 = 1;
	public static final int SCP_01_15 = 2;
	public static final int SCP_02_04 = 3;
	public static final int SCP_02_05 = 4;
	public static final int SCP_02_0A = 5;
	public static final int SCP_02_0B = 6;
	public static final int SCP_02_14 = 7;
	public static final int SCP_02_15 = 8;
	public static final int SCP_02_1A = 9;
	public static final int SCP_02_1B = 10;
	public static final int APDU_CLR = 0x00;
	public static final int APDU_MAC = 0x01;
	public static final int APDU_ENC = 0x02;
	public static final int APDU_RMAC = 0x10;
	public static final byte CLA_GP = (byte) 0x80;
	public static final byte CLA_MAC = (byte) 0x84;
	public static final byte INS_INITIALIZE_UPDATE = (byte) 0x50;
	public static final byte INS_INSTALL = (byte) 0xE6;
	public static final byte INS_LOAD = (byte) 0xE8;
	public static final byte INS_DELETE = (byte) 0xE4;
	public static final byte INS_GP_GET_STATUS_F2 = (byte) 0xF2;

	// AID of the card successfully selected or null
	protected AID sdAID = null;

	public static final byte[] defaultKey = { 0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E, 0x4F };

	static final IvParameterSpec iv_null = new IvParameterSpec(new byte[] { 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 });
	public static Map<String, byte[]> SPECIAL_MOTHER_KEYS = new TreeMap<String, byte[]>();


	public static final int defaultLoadSize = 255;
	protected SecureChannelWrapper wrapper = null;
	protected CardChannel channel = null;
	protected int scpVersion = SCP_ANY;
	private final HashMap<Integer, KeySet> keys = new HashMap<Integer, KeySet>();

	protected boolean verbose = false;
	protected boolean debug = false;
	protected boolean strict = true;


	/**
	 * Set the channel and use the default security domain AID and scpAny.
	 *
	 * @param channel
	 *            channel to talk to
	 * @throws IllegalArgumentException
	 *             if {@code channel} is null.
	 */
	public GlobalPlatform(CardChannel channel) {
		this.channel = channel;
	}

	/**
	 * Set the channel and the scpVersion and use the default security domain
	 * AID.
	 *
	 * @param channel
	 *            channel to talk to
	 * @param scpVersion
	 * @throws IllegalArgumentException
	 *             if {@code scpVersion} is out of range or {@code channel} is
	 *             null.
	 */
	//	public GlobalPlatform(CardChannel channel, int scpVersion) {
	//		if ((scpVersion != SCP_ANY) && (scpVersion != SCP_02_0A) && (scpVersion != SCP_02_0B) && (scpVersion != SCP_02_1A) && (scpVersion != SCP_02_1B)) {
	//			throw new IllegalArgumentException("Only implicit secure channels can be set through the constructor.");
	//		}
	//		this.channel = channel;
	//		this.scpVersion = scpVersion;
	//	}

	public void setVerbose(boolean verbose) {
		this.verbose = verbose;
	}

	public void setStrict(boolean strict) {
		this.strict = strict;
	}

	public void setDebug(boolean debug) {
		this.debug = debug;
	}

	protected void printStrictWarning(String message) throws GPException {
		if (strict) {
			throw new GPException(message);
		} else {
			System.err.println(message);
		}
	}

	public boolean select(AID sdAID) throws GPException, CardException {
		// 1. Try to select ISD without giving the sdAID
		// Works on most cards. Notice the coding of Le in CommandAPDU
		CommandAPDU command = new CommandAPDU(ISO7816.CLA_ISO7816, ISO7816.INS_SELECT, 0x04, 0x00, 256);
		ResponseAPDU resp = channel.transmit(command);

		if (resp.getSW() == 0x6A82) {
			printStrictWarning("Warning - SELECT ISD returned 6A82 - unfused JCOP?");
		}
		if (resp.getSW() == 0x6283) {
			printStrictWarning("Warning - SELECT ISD returned 6283 - CARD_LOCKED");
		}
		if (resp.getSW() == 0x9000 || resp.getSW() == 0x6283) {
			// The security domain AID is in FCI.
			byte[] fci = resp.getData();

			// Skip template information and find tag 0x84
			short aid_offset = TLVUtils.findTag(fci, TLVUtils.skipTagAndLength(fci, (short) 0, (byte) 0x6F), (byte) 0x84);
			int aid_length = TLVUtils.getTagLength(fci, aid_offset);

			AID detectedAID = new AID(fci, aid_offset + 2, aid_length, true);
			if (verbose)
				System.out.println("Auto-detected AID: " + detectedAID);
			if (sdAID != null && !detectedAID.equals(sdAID)) {
				printStrictWarning("sdAID in FCI does not match the requested AID!");
			}
			this.sdAID = sdAID == null ? detectedAID : sdAID;
			return true;
			// TODO: parse the maximum command size as well.
		}
		return false;
	}
	/**
	 * Establish a connection to the security domain specified in the
	 * constructor or discovered. This method is required before doing
	 * {@link #openSecureChannel openSecureChannel}.
	 *
	 * @throws GPException
	 *             if security domain selection fails for some reason
	 * @throws CardException
	 *             on data transmission errors
	 */
	public void select() throws GPException, CardException, IOException {
		// Try to locate the security domain if not given as a parameter.
		if (!select(null)) {
			for (Map.Entry<String, AID> entry : AID.SD_AIDS.entrySet()) {
				if (select(entry.getValue())) {
					break;
				}
			}
		}

		if (this.sdAID == null) {
			throw new GPException("Could not select security domain!");
		}
		if (this.verbose)
			discoverCardProperties();
	}

	public void discoverCardProperties() throws CardException, IOException {

		// Card data
		CommandAPDU command = new CommandAPDU(CLA_GP, ISO7816.INS_GET_DATA, 0x00, 0x66, 256);
		ResponseAPDU resp = channel.transmit(command);
		if (resp.getSW() == 0x6A86) {
			System.out.println("GET DATA(CardData) not supported, Open Platform 2.0.1 card?");
		} else if (resp.getSW() == 0x9000) {
			GlobalPlatformData.pretty_print_card_data(resp.getData());
		}
		// Issuer Identification Number (IIN)
		command = new CommandAPDU(CLA_GP, ISO7816.INS_GET_DATA, 0x00, 0x42, 256);
		resp = channel.transmit(command);
		if (resp.getSW() == 0x9000) {
			System.out.println("IIN " + LoggingCardTerminal.encodeHexString(resp.getData()));
		} else {
			System.out.println("GET DATA(IIN) not supported");
		}

		// Card Image Number (CIN)
		command = new CommandAPDU(CLA_GP, ISO7816.INS_GET_DATA, 0x00, 0x45, 256);
		resp = channel.transmit(command);
		if (resp.getSW() == 0x9000) {
			System.out.println("CIN " + LoggingCardTerminal.encodeHexString(resp.getData()));
		} else {
			System.out.println("GET DATA(CIN) not supported");
		}

		// Key Information Template
		command = new CommandAPDU(CLA_GP, ISO7816.INS_GET_DATA, 0x00, 0xE0, 256);
		resp = channel.transmit(command);
		if (resp.getSW() == 0x9000) {
			GlobalPlatformData.pretty_print_keys(resp.getData(), (short) 0);
		} else {
			System.out.println("GET DATA(Key Information Template) not supported");
		}
		// Sequence Counter of the default Key Version Number
		command = new CommandAPDU(CLA_GP, ISO7816.INS_GET_DATA, 0x00, 0xC1, 256);
		resp = channel.transmit(command);
		if (resp.getSW() == 0x9000) {
			System.out.println("SSC " + LoggingCardTerminal.encodeHexString(resp.getData()));
		} else {
			System.out.println("GET DATA(SSC) not supported");
		}

		// CPLC - not needed to have it.
		command = new CommandAPDU(CLA_GP, ISO7816.INS_GET_DATA, 0x9F, 0x7F, 256);
		resp = channel.transmit(command);
		if (resp.getSW() == 0x9000) {
			System.out.println("CPLC " + LoggingCardTerminal.encodeHexString(resp.getData()));
		} else {
			System.out.println("GET DATA(CPLC) not supported");
		}


	}

	public static byte[] fetchCPLC(boolean iso, CardChannel channel) throws CardException {
		CommandAPDU command = new CommandAPDU(iso ? ISO7816.CLA_ISO7816 : CLA_GP, ISO7816.INS_GET_DATA, 0x9F, 0x7F, 256);
		ResponseAPDU resp = channel.transmit(command);

		if (resp.getSW() == 0x9000) {
			return resp.getData();
		}
		return null;
	}

	public byte[] getCPLC() throws CardException {
		return fetchCPLC(false, this.channel);
	}

	/**
	 * Establishes a secure channel to the security domain. The security domain
	 * must have been selected with {@link open open} before. The {@code keySet}
	 * must have been initialized with {@link setKeys setKeys} before.
	 *
	 * @throws IllegalArgumentException
	 *             if the arguments are out of range or the keyset is undefined
	 * @throws CardException
	 *             if some communication problem is encountered.
	 */
	public void openSecureChannel(KeySet staticKeys, int scpVersion, int securityLevel)
			throws CardException, GPException {

		if ((scpVersion < SCP_ANY) || (scpVersion > SCP_02_1B)) {
			throw new IllegalArgumentException("Invalid SCP version.");
		}

		if ((scpVersion == SCP_02_0A) || (scpVersion == SCP_02_0B) || (scpVersion == SCP_02_1A) || (scpVersion == SCP_02_1B)) {
			throw new IllegalArgumentException("Implicit secure channels cannot be initialized explicitly (use the constructor).");
		}

		//		if ((keySet < 0) || (keySet > 127)) {
		//			throw new IllegalArgumentException("Invalid key set number.");
		//		}

		int mask = ~(APDU_MAC | APDU_ENC | APDU_RMAC);

		if ((securityLevel & mask) != 0) {
			throw new IllegalArgumentException("Wrong security level specification");
		}
		if ((securityLevel & APDU_ENC) != 0) {
			securityLevel |= APDU_MAC;
		}

		byte[] rand = new byte[8];
		SecureRandom sr = new SecureRandom();
		sr.nextBytes(rand);

		// P1 key version (SCP1)
		// P2 either key ID (SCP01) or 0 (SCP2)
		// TODO: use it here?
		CommandAPDU initUpdate = new CommandAPDU(CLA_GP, INS_INITIALIZE_UPDATE, staticKeys.getKeyVersion(), staticKeys.getKeyID(), rand);

		ResponseAPDU response = channel.transmit(initUpdate);
		short sw = (short) response.getSW();

		// Detect and report locked cards in a more sensible way.
		if ((sw == ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED) || (sw == ISO7816.SW_AUTHENTICATION_METHOD_BLOCKED)) {
			throw new GPException(sw, "INITIALIZE UPDATE failed, card LOCKED?");
		}

		// Detect all other errors
		if (sw != ISO7816.SW_NO_ERROR) {
			throw new GPException(sw, "INITIALIZE UPDATE failed");
		}
		byte[] update_response = response.getData();
		if (update_response.length != 28) {
			throw new GPException("Wrong INITIALIZE UPDATE response length: " + update_response.length);
		}
		if (scpVersion == SCP_ANY) {
			scpVersion = update_response[11] == 2 ? SCP_02_15 : SCP_01_05;
		}
		int scp = (scpVersion < SCP_02_04) ? 1 : 2;
		if (scp != update_response[11]) {
			throw new GPException("Secure Channel Protocol version mismatch: " + scp + " vs " + update_response[11]);
		}
		if ((scp == 1) && ((scpVersion & APDU_RMAC) != 0)) {
			scpVersion &= ~APDU_RMAC;
		}

		// Only diversify default key sets that require it.
		if ((staticKeys.getKeyVersion() == 0) || (staticKeys.getKeyVersion() == 255)) {
			if (staticKeys.needsDiversity()) {
				staticKeys.diversify(update_response);
			}
		}

		if ((staticKeys.getKeyVersion() > 0) && ((update_response[10] & 0xff) != staticKeys.getKeyVersion())) {
			throw new GPException("Key set mismatch.");
		}

		KeySet sessionKeys = null;

		if (scp == 1) {
			sessionKeys = deriveSessionKeysSCP01(staticKeys, rand, update_response);
		} else {
			sessionKeys = deriveSessionKeysSCP02(staticKeys, update_response[12], update_response[13], false);
		}

		ByteArrayOutputStream bo = new ByteArrayOutputStream();

		try {
			bo.write(rand);
			bo.write(update_response, 12, 8);
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}

		byte[] myCryptogram = GPUtils.mac_3des(sessionKeys.getKey(KeyType.ENC), GPUtils.pad80(bo.toByteArray()), new byte[8]);

		byte[] cardCryptogram = new byte[8];
		System.arraycopy(update_response, 20, cardCryptogram, 0, 8);
		if (!Arrays.equals(cardCryptogram, myCryptogram)) {
			throw new GPException("Card cryptogram invalid.\nExp: " + GPUtils.byteArrayToString(cardCryptogram)+ "\nAct: "+GPUtils.byteArrayToString(myCryptogram));
		}

		try {
			bo.reset();
			bo.write(update_response, 12, 8);
			bo.write(rand);
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}

		byte[] authData = GPUtils.mac_3des(sessionKeys.getKey(KeyType.ENC), GPUtils.pad80(bo.toByteArray()), new byte[8]);

		wrapper = new SecureChannelWrapper(sessionKeys, scpVersion, APDU_MAC, null, null);
		CommandAPDU externalAuthenticate = new CommandAPDU(CLA_MAC, ISO7816.INS_EXTERNAL_AUTHENTICATE_82, securityLevel, 0, authData);
		response = transmit(externalAuthenticate);
		sw = (short) response.getSW();
		if (sw != ISO7816.SW_NO_ERROR) {
			throw new GPException(sw, "External authenticate failed. SW: " + GPUtils.swToString(sw));
		}
		wrapper.setSecurityLevel(securityLevel);
		if ((securityLevel & APDU_RMAC) != 0) {
			wrapper.ricv = new byte[8];
			System.arraycopy(wrapper.icv, 0, wrapper.ricv, 0, 8);
		}
		this.scpVersion = scpVersion;
	}

	public KeySet deriveSessionKeysSCP01(KeySet staticKeys, byte[] hostRandom, byte[] cardResponse) {
		byte[] derivationData = new byte[16];

		System.arraycopy(cardResponse, 16, derivationData, 0, 4);
		System.arraycopy(hostRandom, 0, derivationData, 4, 4);
		System.arraycopy(cardResponse, 12, derivationData, 8, 4);
		System.arraycopy(hostRandom, 4, derivationData, 12, 4);
		KeySet sessionKeys = new KeySet();

		try {
			Cipher cipher = Cipher.getInstance("DESede/ECB/NoPadding");
			for (KeyType v: KeyType.values()) {
				if (v == KeyType.RMAC) continue;
				cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(staticKeys.get3DES(v), "DESede"));
				sessionKeys.setKey(v, cipher.doFinal(derivationData));
			}
		} catch (BadPaddingException e) {
			throw new RuntimeException("Session keys calculation failed.", e);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("Session keys calculation failed.", e);
		} catch (NoSuchPaddingException e) {
			throw new RuntimeException("Session keys calculation failed.", e);
		} catch (InvalidKeyException e) {
			throw new RuntimeException("Session keys calculation failed.", e);
		} catch (IllegalBlockSizeException e) {
			throw new RuntimeException("Session keys calculation failed.", e);
		}

		// KEK is the same
		sessionKeys.setKey(KeyType.KEK, staticKeys.getKey(KeyType.KEK));
		return sessionKeys;
	}

	public KeySet deriveSessionKeysSCP02(KeySet staticKeys, byte seq1, byte seq2, boolean implicitChannel) throws CardException {
		KeySet sessionKeys = new KeySet();

		try {
			byte[] derivationData = new byte[16];
			derivationData[2] = seq1;
			derivationData[3] = seq2;

			byte[] constantMAC = new byte[] { (byte) 0x01, (byte) 0x01 };
			System.arraycopy(constantMAC, 0, derivationData, 0, 2);

			Cipher cipher = Cipher.getInstance("DESede/CBC/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(staticKeys.get3DES(KeyType.MAC), "DESede"), new IvParameterSpec(new byte[8]));
			sessionKeys.setKey(KeyType.MAC, cipher.doFinal(derivationData));

			// TODO: is this correct?
			if (implicitChannel) {
				if (seq2 == (byte) 0xff) {
					seq2 = (byte) 0;
					seq1++;
				} else {
					seq2++;
				}
				derivationData[2] = seq1;
				derivationData[3] = seq2;
			}

			byte[] constantRMAC = new byte[] { (byte) 0x01, (byte) 0x02 };
			System.arraycopy(constantRMAC, 0, derivationData, 0, 2);


			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(staticKeys.get3DES(KeyType.MAC), "DESede"), new IvParameterSpec(new byte[8]));
			sessionKeys.setKey(KeyType.RMAC, cipher.doFinal(derivationData));;

			byte[] constantENC = new byte[] { (byte) 0x01, (byte) 0x82 };
			System.arraycopy(constantENC, 0, derivationData, 0, 2);

			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(staticKeys.get3DES(KeyType.ENC), "DESede"), new IvParameterSpec(new byte[8]));
			sessionKeys.setKey(KeyType.ENC, cipher.doFinal(derivationData));

			byte[] constantDEK = new byte[] { (byte) 0x01, (byte) 0x81 };
			System.arraycopy(constantDEK, 0, derivationData, 0, 2);

			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(staticKeys.get3DES(KeyType.KEK), "DESede"), new IvParameterSpec(new byte[8]));
			sessionKeys.setKey(KeyType.KEK, cipher.doFinal(derivationData));

		}  catch (BadPaddingException e) {
			throw new RuntimeException("Session keys calculation failed.", e);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("Session keys calculation failed.", e);
		} catch (NoSuchPaddingException e) {
			throw new RuntimeException("Session keys calculation failed.", e);
		} catch (InvalidKeyException e) {
			throw new RuntimeException("Session keys calculation failed.", e);
		} catch (IllegalBlockSizeException e) {
			throw new RuntimeException("Session keys calculation failed.", e);
		} catch (InvalidAlgorithmParameterException e) {
			throw new RuntimeException("Session keys calculation failed.", e);
		}
		return sessionKeys;

	}

	public ResponseAPDU transmit(CommandAPDU command) throws CardException {
		CommandAPDU wc = wrapper.wrap(command);
		ResponseAPDU wr = channel.transmit(wc);
		return wrapper.unwrap(wr);
	}

	public void setKeys(int id, byte[] masterKey, KeyDiversification diversification) {
		keys.put(id, new KeySet(id, 0, masterKey, masterKey, masterKey, diversification));
	}

	/**
	 *
	 * Convenience method, opens {@code fileName} and calls then
	 * {@link #loadCapFile(CapFile, boolean, boolean, int, boolean, boolean)}
	 * with otherwise unmodified parameters.
	 *
	 * @param fileName
	 *            file name of the applet cap file
	 * @param includeDebug
	 * @param separateComponents
	 * @param blockSize
	 * @param loadParam
	 * @param useHash
	 * @throws GPInstallForLoadException
	 *             if the install-for-load command fails with a non 9000
	 *             response status
	 * @throws GPLoadException
	 *             if one of the cap file APDU's fails with a non 9000 response
	 *             status
	 * @throws CardException
	 *             for low-level communication problems
	 * @throws IOException
	 *             if opening {@code fileName} fails
	 */
	public void loadCapFile(URL url, boolean includeDebug, boolean separateComponents, int blockSize, boolean loadParam, boolean useHash)
			throws IOException, GPException, CardException {
		CapFile cap = new CapFile(url.openStream());
		loadCapFile(cap, includeDebug, separateComponents, blockSize, loadParam, useHash);
	}

	public void loadCapFile(CapFile cap, boolean includeDebug, boolean separateComponents, int blockSize, boolean loadParam, boolean useHash)
			throws GPException, CardException {

		byte[] hash = useHash ? cap.getLoadFileDataHash(includeDebug) : new byte[0];
		int len = cap.getCodeLength(includeDebug);
		byte[] loadParams = loadParam ? new byte[] { (byte) 0xEF, 0x04, (byte) 0xC6, 0x02, (byte) ((len & 0xFF00) >> 8),
				(byte) (len & 0xFF) } : new byte[0];

		ByteArrayOutputStream bo = new ByteArrayOutputStream();

		try {
			bo.write(cap.getPackageAID().getLength());
			bo.write(cap.getPackageAID().getBytes());

			bo.write(sdAID.getLength());
			bo.write(sdAID.getBytes());

			bo.write(hash.length);
			bo.write(hash);

			bo.write(loadParams.length);
			bo.write(loadParams);
			bo.write(0);
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}

		CommandAPDU installForLoad = new CommandAPDU(CLA_GP, INS_INSTALL, 0x02, 0x00, bo.toByteArray());
		ResponseAPDU response = transmit(installForLoad);
		short sw = (short) response.getSW();
		if (sw != ISO7816.SW_NO_ERROR) {
			throw new GPException(sw, "Install for Load failed");
		}
		List<byte[]> blocks = cap.getLoadBlocks(includeDebug, separateComponents, blockSize);
		for (int i = 0; i < blocks.size(); i++) {
			CommandAPDU load = new CommandAPDU(CLA_GP, INS_LOAD, (i == (blocks.size() - 1)) ? 0x80 : 0x00, (byte) i, blocks.get(i));
			response = transmit(load);
			sw = (short) response.getSW();
			if (sw != ISO7816.SW_NO_ERROR) {
				throw new GPException(sw, "Load failed");
			}
		}
	}

	/**
	 * Install an applet and make it selectable. The package and applet AID must
	 * be present (ie. non-null). If one of the other parameters is null
	 * sensible defaults are chosen. If installation parameters are used, they
	 * must be passed in a special format, see parameter description below.
	 * <P>
	 * Before installation the package containing the applet must be loaded onto
	 * the card, see {@link #loadCapFile loadCapFile}.
	 * <P>
	 * This method installs just one applet. Call it several times for packages
	 * containing several applets.
	 *
	 * @param packageAID
	 *            the package that containing the applet
	 * @param appletAID
	 *            the applet to be installed
	 * @param instanceAID
	 *            the applet AID passed to the install method of the applet,
	 *            defaults to {@code packageAID} if null
	 * @param privileges
	 *            privileges encoded as byte
	 * @param installParams
	 *            tagged installation parameters, defaults to {@code 0xC9 00}
	 *            (ie. no installation parameters) if null, if non-null the
	 *            format is {@code 0xC9 len data...}
	 */
	public void installAndMakeSelecatable(AID packageAID, AID appletAID, AID instanceAID, byte privileges, byte[] installParams,
			byte[] installToken) throws GPException, CardException {
		if (installParams == null) {
			installParams = new byte[] { (byte) 0xC9, 0x00 };
		}
		if (instanceAID == null) {
			instanceAID = appletAID;
		}
		if (installToken == null) {
			installToken = new byte[0];
		}
		ByteArrayOutputStream bo = new ByteArrayOutputStream();
		try {
			bo.write(packageAID.getLength());
			bo.write(packageAID.getBytes());

			bo.write(appletAID.getLength());
			bo.write(appletAID.getBytes());

			bo.write(instanceAID.getLength());
			bo.write(instanceAID.getBytes());

			bo.write(1);
			bo.write(privileges);
			bo.write(installParams.length);
			bo.write(installParams);

			bo.write(installToken.length);
			bo.write(installToken);
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}

		CommandAPDU install = new CommandAPDU(CLA_GP, INS_INSTALL, 0x0C, 0x00, bo.toByteArray());
		ResponseAPDU response = transmit(install);
		short sw = (short) response.getSW();
		if (sw != ISO7816.SW_NO_ERROR) {
			throw new GPException(sw, "Install for Install and make selectable failed");
		}

	}


	public void makeDefaultSelected(AID aid, byte privileges) throws CardException, GPException {
		ByteArrayOutputStream bo = new ByteArrayOutputStream();
		try {
			bo.write(0);
			bo.write(0);
			bo.write(aid.getLength());
			bo.write(aid.getBytes());
			bo.write(1);
			bo.write(privileges);
			bo.write(0);
			bo.write(0);

			CommandAPDU install = new CommandAPDU(CLA_GP, INS_INSTALL, 0x08, 0x00, bo.toByteArray());
			ResponseAPDU response = transmit(install);
			short sw = (short) response.getSW();
			if (sw != ISO7816.SW_NO_ERROR) {
				throw new GPException(sw, "Install for Install and make selectable failed");
			}

		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}
	}
	/**
	 * Delete file {@code aid} on the card. Delete dependencies as well if
	 * {@code deleteDeps} is true.
	 *
	 * @param aid
	 *            identifier of the file to delete
	 * @param deleteDeps
	 *            if true delete dependencies as well
	 * @throws GPDelete
	 *             if the delete command fails with a non 9000 response status
	 * @throws CardException
	 *             for low-level communication errors
	 */
	public void deleteAID(AID aid, boolean deleteDeps) throws GPException, CardException {
		ByteArrayOutputStream bo = new ByteArrayOutputStream();
		try {
			bo.write(0x4f);
			bo.write(aid.getLength());
			bo.write(aid.getBytes());
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}
		CommandAPDU delete = new CommandAPDU(CLA_GP, INS_DELETE, 0x00, deleteDeps ? 0x80 : 0x00, bo.toByteArray());
		ResponseAPDU response = transmit(delete);
		short sw = (short) response.getSW();
		if (sw != ISO7816.SW_NO_ERROR) {
			throw new GPException(sw, "Deletion failed");
		}
	}

	/**
	 * Get card status. Perform all possible variants of the get status command
	 * and return all entries reported by the card in an AIDRegistry.
	 *
	 * @return registry with all entries on the card
	 * @throws CardException
	 *             in case of communication errors
	 */
	public AIDRegistry getStatus() throws CardException, IOException {
		AIDRegistry registry = new AIDRegistry();
		int[] p1s = { 0x80, 0x40 };
		for (int p1 : p1s) {
			ByteArrayOutputStream bo = new ByteArrayOutputStream();
			CommandAPDU getStatus = new CommandAPDU(CLA_GP, INS_GP_GET_STATUS_F2, p1, 0x00, new byte[] { 0x4F, 0x00 });
			ResponseAPDU response = transmit(getStatus);
			short sw = (short) response.getSW();

			// is SD scope fails, use application scope
			if ((sw != ISO7816.SW_NO_ERROR) && (sw != (short) 0x6310)) {
				continue;
			}

			bo.write(response.getData());

			while (response.getSW() == 0x6310) {
				getStatus = new CommandAPDU(CLA_GP, INS_GP_GET_STATUS_F2, p1, 0x01, new byte[] { 0x4F, 0x00 });
				response = transmit(getStatus);

				bo.write(response.getData());

				sw = (short) response.getSW();
				if ((sw != ISO7816.SW_NO_ERROR) && (sw != (short) 0x6310)) {
					throw new CardException("Get Status failed, SW: " + GPUtils.swToString(sw));
				}
			}
			// parse data no sub-AID
			int index = 0;
			byte[] data = bo.toByteArray();
			while (index < data.length) {
				int len = data[index++];
				AID aid = new AID(data, index, len);
				index += len;
				int life_cycle = data[index++];
				int privileges = data[index++];

				AIDRegistryEntry.Kind kind = AIDRegistryEntry.Kind.IssuerSecurityDomain;
				if (p1 == 0x40) {
					if ((privileges & 0x80) == 0) {
						kind = AIDRegistryEntry.Kind.Application;
					} else {
						kind = AIDRegistryEntry.Kind.SecurityDomain;
					}
				}

				AIDRegistryEntry entry = new AIDRegistryEntry(aid, life_cycle, privileges, kind);
				registry.add(entry);
			}
		}
		p1s = new int[] { 0x10, 0x20 };
		boolean succ10 = false;
		for (int p1 : p1s) {
			if (succ10) {
				continue;
			}
			ByteArrayOutputStream bo = new ByteArrayOutputStream();
			CommandAPDU getStatus = new CommandAPDU(CLA_GP, INS_GP_GET_STATUS_F2, p1, 0x00, new byte[] { 0x4F, 0x00 });
			ResponseAPDU response = transmit(getStatus);
			short sw = (short) response.getSW();
			if ((sw != ISO7816.SW_NO_ERROR) && (sw != (short) 0x6310)) {
				continue;
			}
			if (p1 == 0x10)
			{
				succ10 = true;
				// copy data
			}

			bo.write(response.getData());

			while (response.getSW() == 0x6310) {
				getStatus = new CommandAPDU(CLA_GP, INS_GP_GET_STATUS_F2, p1, 0x01, new byte[] { 0x4F, 0x00 });
				response = transmit(getStatus);
				bo.write(response.getData());

				sw = (short) response.getSW();
				if ((sw != ISO7816.SW_NO_ERROR) && (sw != (short) 0x6310)) {
					throw new CardException("Get Status failed, SW: " + GPUtils.swToString(sw));
				}
			}

			int index = 0;
			byte[] data = bo.toByteArray();
			while (index < data.length) {
				int len = data[index++];
				AID aid = new AID(data, index, len);
				index += len;
				AIDRegistryEntry entry = new AIDRegistryEntry(aid, data[index++], data[index++],
						p1 == 0x10 ? AIDRegistryEntry.Kind.ExecutableLoadFilesAndModules : AIDRegistryEntry.Kind.ExecutableLoadFiles);
				if (p1 == 0x10) {
					int num = data[index++];
					for (int i = 0; i < num; i++) {
						len = data[index++];
						aid = new AID(data, index, len);
						index += len;
						entry.addExecutableAID(aid);
					}
				}
				registry.add(entry);
			}
		}
		return registry;
	}



	public class SecureChannelWrapper {
		private KeySet sessionKeys = null;
		private byte[] icv = null;
		private byte[] ricv = null;
		private int scp = 0;

		private final ByteArrayOutputStream rMac = new ByteArrayOutputStream();

		private boolean icvEnc = false;

		private boolean preAPDU = false;
		private boolean postAPDU = false;

		private boolean mac = false;
		private boolean enc = false;
		private boolean rmac = false;

		private SecureChannelWrapper(KeySet sessionKeys, int scp, int securityLevel, byte[] icv, byte[] ricv) {
			this.sessionKeys = sessionKeys;
			this.icv = icv;
			this.ricv = ricv;
			setSCPVersion(scp);
			setSecurityLevel(securityLevel);
		}

		public void setSecurityLevel(int securityLevel) {
			if ((securityLevel & APDU_MAC) != 0) {
				mac = true;
			} else {
				mac = false;
			}
			if ((securityLevel & APDU_ENC) != 0) {
				enc = true;
			} else {
				enc = false;
			}

			if ((securityLevel & APDU_RMAC) != 0) {
				rmac = true;
			} else {
				rmac = false;
			}
		}

		public void setSCPVersion(int scp) {
			this.scp = 2;
			if (scp < SCP_02_04) {
				this.scp = 1;
			}
			if ((scp == SCP_01_15) || (scp == SCP_02_14) || (scp == SCP_02_15) || (scp == SCP_02_1A) || (scp == SCP_02_1B)) {
				icvEnc = true;
			} else {
				icvEnc = false;
			}

			if ((scp == SCP_01_05) || (scp == SCP_01_15) || (scp == SCP_02_04) || (scp == SCP_02_05) || (scp == SCP_02_14) || (scp == SCP_02_15)) {
				preAPDU = true;
			} else {
				preAPDU = false;
			}
			if ((scp == SCP_02_0A) || (scp == SCP_02_0B) || (scp == SCP_02_1A) || (scp == SCP_02_1B)) {
				postAPDU = true;
			} else {
				postAPDU = false;
			}
		}

		private byte clearBits(byte b, byte mask) {
			return (byte) ((b & ~mask) & 0xFF);
		}

		private byte setBits(byte b, byte mask) {
			return (byte) ((b | mask) & 0xFF);
		}

		private CommandAPDU wrap(CommandAPDU command) throws CardException {
			try {
				if (rmac) {
					rMac.reset();
					rMac.write(clearBits((byte) command.getCLA(), (byte) 0x07));
					rMac.write(command.getINS());
					rMac.write(command.getP1());
					rMac.write(command.getP2());
					if (command.getNc() >= 0) {
						rMac.write(command.getNc());
						rMac.write(command.getData());
					}
				}
				if (!mac && !enc) {
					return command;
				}


				int origCLA = command.getCLA();
				int newCLA = origCLA;
				int origINS = command.getINS();
				int origP1 = command.getP1();
				int origP2 = command.getP2();
				byte[] origData = command.getData();
				int origLc = command.getNc();
				int newLc = origLc;
				byte[] newData = null;
				int le = command.getNe();
				ByteArrayOutputStream t = new ByteArrayOutputStream();

				// TODO: get from CardData
				int maxLen = 255;

				if (mac) {
					maxLen -= 8;
				}
				if (enc) {
					maxLen -= 8;
				}

				if (origLc > maxLen) {
					throw new CardException("APDU too long for wrapping.");
				}

				if (mac) {
					if (icv == null) {
						icv = new byte[8];
					} else if (icvEnc) {
						Cipher c = null;
						if (scp == 1) {
							c = Cipher.getInstance("DESede/ECB/NoPadding");
							c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sessionKeys.get3DES(KeyType.MAC), "DESede"));

						} else {
							c = Cipher.getInstance("DES/ECB/NoPadding");
							c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sessionKeys.getDES(KeyType.MAC), "DES"));
						}
						// encrypts the future ICV ?
						icv = c.doFinal(icv);
					}

					if (preAPDU) {
						newCLA = setBits((byte) newCLA, (byte) 0x04);
						newLc = newLc + 8;
					}
					t.write(newCLA);
					t.write(origINS);
					t.write(origP1);
					t.write(origP2);
					t.write(newLc);
					t.write(origData);

					if (scp == 1) {
						icv = GPUtils.mac_3des(sessionKeys.getKey(KeyType.MAC), GPUtils.pad80(t.toByteArray()), icv);
					} else {
						icv = GPUtils.mac_des_3des(sessionKeys.getKey(KeyType.MAC), GPUtils.pad80(t.toByteArray()), icv);
					}

					if (postAPDU) {
						newCLA = setBits((byte) newCLA, (byte) 0x04);
						newLc = newLc + 8;
					}
					t.reset();
					newData = origData;
				}

				if (enc && (origLc > 0)) {
					if (scp == 1) {
						t.write(origLc);
						t.write(origData);
						if ((t.size() % 8) != 0) {
							byte[] x = GPUtils.pad80(t.toByteArray());
							t.reset();
							t.write(x);
						}
					} else {
						t.write(GPUtils.pad80(origData));
					}
					newLc += t.size() - origData.length;

					Cipher c = Cipher.getInstance("DESede/CBC/NoPadding");
					c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sessionKeys.get3DES(KeyType.ENC), "DESede"), iv_null);
					newData = c.doFinal(t.toByteArray());
					t.reset();
				}
				t.write(newCLA);
				t.write(origINS);
				t.write(origP1);
				t.write(origP2);
				if (newLc > 0) {
					t.write(newLc);
					t.write(newData);
				}
				if (mac) {
					t.write(icv);
				}
				if (le > 0) {
					t.write(le);
				}
				CommandAPDU wrapped = new CommandAPDU(t.toByteArray());
				return wrapped;
			} catch (CardException ce) {
				throw ce;
			} catch (IOException e) {
				throw new RuntimeException("APDU wrapping failed", e);
			} catch (NoSuchAlgorithmException e) {
				throw new RuntimeException("APDU wrapping failed", e);
			} catch (NoSuchPaddingException e) {
				throw new RuntimeException("APDU wrapping failed", e);
			} catch (InvalidKeyException e) {
				throw new RuntimeException("APDU wrapping failed", e);
			} catch (InvalidAlgorithmParameterException e) {
				throw new RuntimeException("APDU wrapping failed", e);
			} catch (IllegalBlockSizeException e) {
				throw new RuntimeException("APDU wrapping failed", e);
			} catch (BadPaddingException e) {
				throw new RuntimeException("APDU wrapping failed", e);
			}
		}

		private ResponseAPDU unwrap(ResponseAPDU response) throws CardException {
			if (rmac) {
				if (response.getData().length < 8) {
					throw new CardException("Wrong response length (too short).");
				}
				int respLen = response.getData().length - 8;
				rMac.write(respLen);
				rMac.write(response.getData(), 0, respLen);
				rMac.write(response.getSW1());
				rMac.write(response.getSW2());

				ricv = GPUtils.mac_des_3des(sessionKeys.getKey(KeyType.RMAC), GPUtils.pad80(rMac.toByteArray()), ricv);

				byte[] actualMac = new byte[8];
				System.arraycopy(response.getData(), respLen, actualMac, 0, 8);
				if (!Arrays.equals(ricv, actualMac)) {
					throw new CardException("RMAC invalid.");
				}
				ByteArrayOutputStream o = new ByteArrayOutputStream();
				o.write(response.getBytes(), 0, respLen);
				o.write(response.getSW1());
				o.write(response.getSW2());
				response = new ResponseAPDU(o.toByteArray());
			}
			return response;
		}
	}
}