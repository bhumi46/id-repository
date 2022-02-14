package io.mosip.idrepository.identity.service.impl;

import static io.mosip.idrepository.core.constant.IdRepoConstants.CBEFF_FORMAT;
import static io.mosip.idrepository.core.constant.IdRepoConstants.FILE_FORMAT_ATTRIBUTE;
import static io.mosip.idrepository.core.constant.IdRepoConstants.FILE_NAME_ATTRIBUTE;
import static io.mosip.idrepository.core.constant.IdRepoConstants.SPLITTER;
import static io.mosip.idrepository.core.constant.IdRepoErrorConstants.ID_OBJECT_PROCESSING_FAILED;
import static io.mosip.idrepository.core.constant.IdRepoErrorConstants.INVALID_INPUT_PARAMETER;
import static io.mosip.idrepository.core.constant.IdRepoErrorConstants.NO_RECORD_FOUND;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.annotation.Resource;

import org.apache.commons.lang3.RegExUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.skyscreamer.jsonassert.FieldComparisonFailure;
import org.skyscreamer.jsonassert.JSONCompare;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.skyscreamer.jsonassert.JSONCompareResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;

import io.mosip.idrepository.core.constant.CredentialRequestStatusLifecycle;
import io.mosip.idrepository.core.constant.IdType;
import io.mosip.idrepository.core.dto.DocumentsDTO;
import io.mosip.idrepository.core.dto.IdRequestDTO;
import io.mosip.idrepository.core.dto.RequestDTO;
import io.mosip.idrepository.core.entity.CredentialRequestStatus;
import io.mosip.idrepository.core.exception.IdRepoAppException;
import io.mosip.idrepository.core.exception.IdRepoAppUncheckedException;
import io.mosip.idrepository.core.logger.IdRepoLogger;
import io.mosip.idrepository.core.repository.CredentialRequestStatusRepo;
import io.mosip.idrepository.core.repository.UinEncryptSaltRepo;
import io.mosip.idrepository.core.repository.UinHashSaltRepo;
import io.mosip.idrepository.core.security.IdRepoSecurityManager;
import io.mosip.idrepository.core.spi.IdRepoService;
import io.mosip.idrepository.core.util.DummyPartnerCheckUtil;
import io.mosip.idrepository.core.util.EnvUtil;
import io.mosip.idrepository.identity.entity.Uin;
import io.mosip.idrepository.identity.entity.UinBiometric;
import io.mosip.idrepository.identity.entity.UinBiometricHistory;
import io.mosip.idrepository.identity.entity.UinDocument;
import io.mosip.idrepository.identity.entity.UinDocumentHistory;
import io.mosip.idrepository.identity.entity.UinHistory;
import io.mosip.idrepository.identity.helper.AnonymousProfileHelper;
import io.mosip.idrepository.identity.helper.ObjectStoreHelper;
import io.mosip.idrepository.identity.repository.UinBiometricHistoryRepo;
import io.mosip.idrepository.identity.repository.UinDocumentHistoryRepo;
import io.mosip.idrepository.identity.repository.UinHistoryRepo;
import io.mosip.idrepository.identity.repository.UinRepo;
import io.mosip.kernel.biometrics.constant.BiometricType;
import io.mosip.kernel.biometrics.entities.BIR;
import io.mosip.kernel.biometrics.spi.CbeffUtil;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.CryptoUtil;
import io.mosip.kernel.core.util.DateUtils;
import io.mosip.kernel.core.util.UUIDUtils;

/**
 * The Class IdRepoServiceImpl - Service implementation for Identity service.
 */
@Component
@Primary
@Transactional(rollbackFor = { IdRepoAppException.class, IdRepoAppUncheckedException.class })
public class IdRepoServiceImpl implements IdRepoService<IdRequestDTO, Uin> {

	private static final String VERIFIED_ATTRIBUTES = "verifiedAttributes";

	/** The Constant UPDATE_IDENTITY. */
	private static final String UPDATE_IDENTITY = "updateIdentity";

	/** The Constant ROOT. */
	private static final String ROOT = "$";

	/** The Constant OPEN_SQUARE_BRACE. */
	private static final String OPEN_SQUARE_BRACE = "[";

	/** The Constant LANGUAGE. */
	private static final String LANGUAGE = "language";

	/** The Constant ADD_IDENTITY. */
	private static final String ADD_IDENTITY = "addIdentity";

	/** The mosip logger. */
	Logger mosipLogger = IdRepoLogger.getLogger(IdRepoServiceImpl.class);

	/** The Constant TYPE. */
	private static final String TYPE = "type";

	/** The Constant DOT. */
	private static final String DOT = ".";

	/** The Constant ID_REPO_SERVICE_IMPL. */
	private static final String ID_REPO_SERVICE_IMPL = "IdRepoServiceImpl";
	
	private static final String RETRIEVE_IDENTITY = "retrieveIdentity";

	/** The env. */
	@Autowired
	protected EnvUtil env;

	/** The mapper. */
	@Autowired
	protected ObjectMapper mapper;

	/** The uin repo. */
	@Autowired
	protected UinRepo uinRepo;

	/** The uin detail repo. */
	@Autowired
	private UinDocumentHistoryRepo uinDocHRepo;

	/** The uin bio H repo. */
	@Autowired
	private UinBiometricHistoryRepo uinBioHRepo;

	/** The uin history repo. */
	@Autowired
	protected UinHistoryRepo uinHistoryRepo;

	/** The cbeff util. */
	@Autowired
	protected CbeffUtil cbeffUtil;

	/** The security manager. */
	@Autowired
	protected IdRepoSecurityManager securityManager;

	/** The bio attributes. */
	@Resource
	protected List<String> bioAttributes;

	/** The uin hash salt repo. */
	@Autowired
	protected UinHashSaltRepo uinHashSaltRepo;

	/** The uin encrypt salt repo. */
	@Autowired
	protected UinEncryptSaltRepo uinEncryptSaltRepo;

	@Autowired
	protected ObjectStoreHelper objectStoreHelper;

	@Autowired
	private DummyPartnerCheckUtil dummyPartner;

	@Autowired
	private CredentialRequestStatusRepo credRequestRepo;
	
	@Autowired
	protected AnonymousProfileHelper anonymousProfileHelper;
	
	@Value("${mosip.idrepo.identity.uin-status.registered}")
	private String activeStatus;
	
	/**
	 * Adds the identity to DB.
	 *
	 * @param request the request
	 * @param uin     the uin
	 * @return the uin
	 * @throws IdRepoAppException the id repo app exception
	 */
	@Override
	public Uin addIdentity(IdRequestDTO request, String uin) throws IdRepoAppException {
		String uinRefId = UUIDUtils.getUUID(UUIDUtils.NAMESPACE_OID, uin + SPLITTER + DateUtils.getUTCCurrentDateTime())
				.toString();
		ObjectNode identityObject = mapper.convertValue(request.getRequest().getIdentity(), ObjectNode.class);
		identityObject.putPOJO(VERIFIED_ATTRIBUTES, request.getRequest().getVerifiedAttributes());
		byte[] identityInfo = convertToBytes(identityObject);
		int modResult = securityManager.getSaltKeyForId(uin);
		String uinHash = getUinHash(uin, modResult);
		String uinHashWithSalt = uinHash.split(SPLITTER)[1];
		String uinToEncrypt = getUinToEncrypt(uin, modResult);
		anonymousProfileHelper
			.setRegId(request.getRequest().getRegistrationId())
			.setNewUinData(identityInfo);

		List<UinDocument> docList = new ArrayList<>();
		List<UinBiometric> bioList = new ArrayList<>();
		Uin uinEntity;
		if (Objects.nonNull(request.getRequest().getDocuments()) && !request.getRequest().getDocuments().isEmpty()) {
			addDocuments(uinHashWithSalt, identityInfo, request.getRequest().getDocuments(), uinRefId, docList, bioList,
					false);
			uinEntity = new Uin(uinRefId, uinToEncrypt, uinHash, identityInfo, securityManager.hash(identityInfo),
					request.getRequest().getRegistrationId(), activeStatus, IdRepoSecurityManager.getUser(),
					DateUtils.getUTCCurrentDateTime(), null, null, false, null, bioList, docList);
			uinEntity = uinRepo.save(uinEntity);
			mosipLogger.debug(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, ADD_IDENTITY,
					"Record successfully saved in db with documents");
		} else {
			uinEntity = new Uin(uinRefId, uinToEncrypt, uinHash, identityInfo, securityManager.hash(identityInfo),
					request.getRequest().getRegistrationId(), activeStatus, IdRepoSecurityManager.getUser(),
					DateUtils.getUTCCurrentDateTime(), null, null, false, null, null, null);
			uinEntity = uinRepo.save(uinEntity);
			mosipLogger.debug(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, ADD_IDENTITY,
					"Record successfully saved in db without documents");
		}

		uinHistoryRepo.save(
				new UinHistory(uinRefId, DateUtils.getUTCCurrentDateTime(), uinEntity.getUin(), uinEntity.getUinHash(),
						uinEntity.getUinData(), uinEntity.getUinDataHash(), uinEntity.getRegId(), activeStatus,
						IdRepoSecurityManager.getUser(), DateUtils.getUTCCurrentDateTime(), null, null, false, null));
		issueCredential(uinEntity.getUin(), uinHashWithSalt, activeStatus, null);
		anonymousProfileHelper.buildAndsaveProfile(false);
		return uinEntity;
	}

	protected String getUinToEncrypt(String uin, int modResult) {
		String encryptSalt = uinEncryptSaltRepo.retrieveSaltById(modResult);
		return modResult + SPLITTER + uin + SPLITTER + encryptSalt;
	}

	protected String getUinHash(String uin, int modResult) {
		String hashSalt = uinHashSaltRepo.retrieveSaltById(modResult);
		return modResult + SPLITTER + securityManager.hashwithSalt(uin.getBytes(), hashSalt.getBytes());
	}

	/**
	 * Stores the documents to FileSystem.
	 *
	 * @param uinHash      the uin hash
	 * @param identityInfo the identity info
	 * @param documents    the documents
	 * @param uinRefId     the uin ref id
	 * @param docList      the doc list
	 * @param bioList      the bio list
	 * @throws IdRepoAppException the id repo app exception
	 */
	private void addDocuments(String uinHash, byte[] identityInfo, List<DocumentsDTO> documents, String uinRefId,
			List<UinDocument> docList, List<UinBiometric> bioList, boolean isDraft) {
		ObjectNode identityObject = convertToObject(identityInfo, ObjectNode.class);
		IntStream.range(0, documents.size()).filter(index -> identityObject.has(documents.get(index).getCategory())).forEach(index -> {
			DocumentsDTO doc = documents.get(index);
			JsonNode docType = identityObject.get(doc.getCategory());
			try {
				if (bioAttributes.contains(doc.getCategory())) {
					addBiometricDocuments(uinHash, uinRefId, bioList, doc, docType, isDraft, index);
					anonymousProfileHelper.setNewCbeff(doc.getValue());
				} else {
					addDemographicDocuments(uinHash, uinRefId, docList, doc, docType, isDraft);
				}
			} catch (IdRepoAppException e) {
				mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, ADD_IDENTITY, e.getMessage());
				throw new IdRepoAppUncheckedException(e.getErrorCode(), e.getErrorText(), e);
			}
		});
	}

	/**
	 * Stores the biometric documents to FileSystem.
	 *
	 * @param uinHash  the uin hash
	 * @param uinRefId the uin ref id
	 * @param bioList  the bio list
	 * @param doc      the doc
	 * @param docType  the doc type
	 * @throws IdRepoAppException the id repo app exception
	 */
	private void addBiometricDocuments(String uinHash, String uinRefId, List<UinBiometric> bioList, DocumentsDTO doc,
			JsonNode docType, boolean isDraft, int index) throws IdRepoAppException {
		byte[] data = null;
		String fileRefId = UUIDUtils
				.getUUID(UUIDUtils.NAMESPACE_OID,
						docType.get(FILE_NAME_ATTRIBUTE).asText() + SPLITTER + DateUtils.getUTCCurrentDateTime())
				.toString() + DOT + docType.get(FILE_FORMAT_ATTRIBUTE).asText();

		data = CryptoUtil.decodeURLSafeBase64(doc.getValue());
		try {
			cbeffUtil.validateXML(data);
		} catch (Exception e) {
			mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, "addBiometricDocuments", e.getMessage());
			throw new IdRepoAppUncheckedException(INVALID_INPUT_PARAMETER.getErrorCode(),
					String.format(INVALID_INPUT_PARAMETER.getErrorMessage(), "documents/" + index + "/value"), e);
		}
		objectStoreHelper.putBiometricObject(uinHash, fileRefId, data);

		bioList.add(new UinBiometric(uinRefId, fileRefId, doc.getCategory(), docType.get(FILE_NAME_ATTRIBUTE).asText(),
				securityManager.hash(data), "", IdRepoSecurityManager.getUser(),
				DateUtils.getUTCCurrentDateTime(), null, null, false, null));

		if (!isDraft)
			uinBioHRepo.save(new UinBiometricHistory(uinRefId, DateUtils.getUTCCurrentDateTime(), fileRefId, doc.getCategory(),
					docType.get(FILE_NAME_ATTRIBUTE).asText(), securityManager.hash(doc.getValue().getBytes()),
					"", IdRepoSecurityManager.getUser(), DateUtils.getUTCCurrentDateTime(),
					null, null, false, null));
	}

	/**
	 * Stores the demographic documents to FileSystem.
	 *
	 * @param uinHash  the uin hash
	 * @param uinRefId the uin ref id
	 * @param docList  the doc list
	 * @param doc      the doc
	 * @param docType  the doc type
	 * @throws IdRepoAppException the id repo app exception
	 */
	private void addDemographicDocuments(String uinHash, String uinRefId, List<UinDocument> docList, DocumentsDTO doc,
			JsonNode docType, boolean isDraft) throws IdRepoAppException {
		String fileRefId = UUIDUtils
				.getUUID(UUIDUtils.NAMESPACE_OID,
						docType.get(FILE_NAME_ATTRIBUTE).asText() + SPLITTER + DateUtils.getUTCCurrentDateTime())
				.toString() + DOT + docType.get(FILE_FORMAT_ATTRIBUTE).asText();

		byte[] data = CryptoUtil.decodeURLSafeBase64(doc.getValue());
		objectStoreHelper.putDemographicObject(uinHash, fileRefId, data);

		docList.add(new UinDocument(uinRefId, doc.getCategory(), docType.get(TYPE).asText(), fileRefId,
				docType.get(FILE_NAME_ATTRIBUTE).asText(), docType.get(FILE_FORMAT_ATTRIBUTE).asText(),
				securityManager.hash(data), "", IdRepoSecurityManager.getUser(),
				DateUtils.getUTCCurrentDateTime(), null, null, false, null));

		if (!isDraft)
			uinDocHRepo.save(new UinDocumentHistory(uinRefId, DateUtils.getUTCCurrentDateTime(), doc.getCategory(),
					docType.get(TYPE).asText(), fileRefId, docType.get(FILE_NAME_ATTRIBUTE).asText(),
					docType.get(FILE_FORMAT_ATTRIBUTE).asText(), securityManager.hash(data),
					"", IdRepoSecurityManager.getUser(), DateUtils.getUTCCurrentDateTime(),
					null, null, false, null));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see io.mosip.idrepository.core.spi.IdRepoService#retrieveIdentity(java.lang.
	 * String, io.mosip.idrepository.core.constant.IdType, java.lang.String)
	 */
	@Override
	public Uin retrieveIdentity(String id, IdType idType, String type, Map<String, String> extractionFormats)
			throws IdRepoAppException {
		Optional<Uin> uinObjOptional = uinRepo.findByUinHash(id);
		if (uinObjOptional.isPresent()) {
			return uinObjOptional.get();
		} else {
			mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, RETRIEVE_IDENTITY,
					NO_RECORD_FOUND.getErrorMessage());
			throw new IdRepoAppException(NO_RECORD_FOUND);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see io.mosip.kernel.core.idrepo.spi.IdRepoService#updateIdentity(java.lang.
	 * Object, java.lang.String)
	 */
	@Override
	public Uin updateIdentity(IdRequestDTO request, String uin) throws IdRepoAppException {
		anonymousProfileHelper.setRegId(request.getRequest().getRegistrationId());
		int modResult = securityManager.getSaltKeyForId(uin);
		String uinHash = getUinHash(uin, modResult);
		String uinHashWithSalt = uinHash.split(SPLITTER)[1];
		try {
			Uin uinObject = retrieveIdentity(uinHash, IdType.UIN, null, null);
			anonymousProfileHelper.setOldCbeff(uinHash,
					!anonymousProfileHelper.isOldCbeffPresent() && Objects.nonNull(uinObject.getBiometrics())
							&& !uinObject.getBiometrics().isEmpty()
									? uinObject.getBiometrics().get(uinObject.getBiometrics().size() - 1).getBioFileId()
									: null);
			uinObject.setRegId(request.getRequest().getRegistrationId());
			if (Objects.nonNull(request.getRequest().getStatus())
					&& !StringUtils.equals(uinObject.getStatusCode(), request.getRequest().getStatus())) {
				uinObject.setStatusCode(request.getRequest().getStatus());
				uinObject.setUpdatedBy(IdRepoSecurityManager.getUser());
				uinObject.setUpdatedDateTime(DateUtils.getUTCCurrentDateTime());
			}
			if (Objects.nonNull(request.getRequest()) && Objects.nonNull(request.getRequest().getIdentity())) {
				RequestDTO requestDTO = request.getRequest();
				Configuration configuration = Configuration.builder().jsonProvider(new JacksonJsonProvider())
						.mappingProvider(new JacksonMappingProvider()).build();
				DocumentContext inputData = JsonPath.using(configuration).parse(requestDTO.getIdentity());
				DocumentContext dbData = JsonPath.using(configuration).parse(new String(uinObject.getUinData()));
				anonymousProfileHelper.setOldUinData(dbData.jsonString().getBytes());
				updateVerifiedAttributes(requestDTO, inputData, dbData);
				JSONCompareResult comparisonResult = JSONCompare.compareJSON(inputData.jsonString(),
						dbData.jsonString(), JSONCompareMode.LENIENT);

				if (comparisonResult.failed()) {
					updateJsonObject(inputData, dbData, comparisonResult);
				}
				uinObject.setUinData(convertToBytes(convertToObject(dbData.jsonString().getBytes(), Map.class)));
				uinObject.setUinDataHash(securityManager.hash(uinObject.getUinData()));
				uinObject.setUpdatedBy(IdRepoSecurityManager.getUser());
				uinObject.setUpdatedDateTime(DateUtils.getUTCCurrentDateTime());

				if (Objects.nonNull(requestDTO.getDocuments()) && !requestDTO.getDocuments().isEmpty()) {
					anonymousProfileHelper
							.setNewCbeff(uinObject.getUinHash(),
									!anonymousProfileHelper.isNewCbeffPresent() ?
									uinObject.getBiometrics().get(uinObject.getBiometrics().size() - 1).getBioFileId()
											: null);
					updateDocuments(uinHashWithSalt, uinObject, requestDTO, false);
					uinObject.setUpdatedBy(IdRepoSecurityManager.getUser());
					uinObject.setUpdatedDateTime(DateUtils.getUTCCurrentDateTime());
				}
			}

			uinObject = uinRepo.save(uinObject);
			anonymousProfileHelper.setNewUinData(uinObject.getUinData());
			uinHistoryRepo.save(new UinHistory(uinObject.getUinRefId(), DateUtils.getUTCCurrentDateTime(),
					uinObject.getUin(), uinObject.getUinHash(), uinObject.getUinData(), uinObject.getUinDataHash(),
					uinObject.getRegId(), uinObject.getStatusCode(), IdRepoSecurityManager.getUser(),
					DateUtils.getUTCCurrentDateTime(), IdRepoSecurityManager.getUser(),
					DateUtils.getUTCCurrentDateTime(), false, null));
			issueCredential(uinObject.getUin(), uinHashWithSalt, uinObject.getStatusCode(),
					DateUtils.getUTCCurrentDateTime());
			anonymousProfileHelper.buildAndsaveProfile(false);
			return uinObject;
		} catch (JSONException | InvalidJsonException e) {
			mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, UPDATE_IDENTITY, e.getMessage());
			throw new IdRepoAppException(ID_OBJECT_PROCESSING_FAILED, e);
		} catch (IdRepoAppUncheckedException e) {
			mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, UPDATE_IDENTITY,
					"\n" + e.getErrorText());
			throw new IdRepoAppException(e.getErrorCode(), e.getErrorText(), e);
		}
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	protected void updateVerifiedAttributes(RequestDTO requestDTO, DocumentContext inputData, DocumentContext dbData) {
		List dbVerifiedAttributes = (List) dbData.read(".verifiedAttributes");
		dbVerifiedAttributes.remove(null);
		if (dbVerifiedAttributes.isEmpty()) {
			dbVerifiedAttributes.add(new ArrayList<>());
		}
		List verifiedAttributeList = (List) dbVerifiedAttributes.get(0);
		if (Objects.nonNull(requestDTO.getVerifiedAttributes())) {
			verifiedAttributeList.addAll(requestDTO.getVerifiedAttributes());
		}
		HashSet<String> verifiedAttributesSet = new HashSet<>(verifiedAttributeList);
		inputData.put("$", VERIFIED_ATTRIBUTES, verifiedAttributesSet);
		dbData.put("$", VERIFIED_ATTRIBUTES, verifiedAttributesSet);
	}

	/**
	 * Update identity.
	 *
	 * @param inputData        the input data
	 * @param dbData           the db data
	 * @param comparisonResult the comparison result
	 * @throws JSONException      the JSON exception
	 * @throws IdRepoAppException the id repo app exception
	 */
	protected void updateJsonObject(DocumentContext inputData, DocumentContext dbData, JSONCompareResult comparisonResult)
			throws JSONException, IdRepoAppException {
		if (comparisonResult.isMissingOnField()) {
			updateMissingFields(dbData, comparisonResult);
		}

		comparisonResult = JSONCompare.compareJSON(inputData.jsonString(), dbData.jsonString(), JSONCompareMode.LENIENT);
		if (comparisonResult.isFailureOnField()) {
			updateFailingFields(inputData, dbData, comparisonResult);
		}

		comparisonResult = JSONCompare.compareJSON(inputData.jsonString(), dbData.jsonString(), JSONCompareMode.LENIENT);
		if (!comparisonResult.getMessage().isEmpty()) {
			updateMissingValues(inputData, dbData, comparisonResult);
		}

		comparisonResult = JSONCompare.compareJSON(inputData.jsonString(), dbData.jsonString(), JSONCompareMode.LENIENT);
		if (comparisonResult.failed()) {
			// Code should never reach here
			updateJsonObject(inputData, dbData, comparisonResult);
		}
	}

	/**
	 * Update missing fields.
	 *
	 * @param dbData           the db data
	 * @param comparisonResult the comparison result
	 * @throws IdRepoAppException the id repo app exception
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private void updateMissingFields(DocumentContext dbData, JSONCompareResult comparisonResult) {
		for (FieldComparisonFailure failure : comparisonResult.getFieldMissing()) {
			if (StringUtils.contains(failure.getField(), OPEN_SQUARE_BRACE)) {
				String path = StringUtils.substringBefore(failure.getField(), OPEN_SQUARE_BRACE);
				String key = StringUtils.substringAfterLast(path, DOT);
				path = StringUtils.substringBeforeLast(path, DOT);

				if (StringUtils.isEmpty(key)) {
					key = path;
					path = ROOT;
				}
				List value = dbData.read(path + DOT + key, List.class);
				value.addAll(Collections
						.singletonList(convertToObject(failure.getExpected().toString().getBytes(), Map.class)));

				dbData.put(path, key, value);
			} else {
				String path = StringUtils.substringBeforeLast(failure.getField(), DOT);
				if (StringUtils.isEmpty(path)) {
					path = ROOT;
				}
				String key = StringUtils.substringAfterLast(failure.getField(), DOT);
				dbData.put(path, (String) failure.getExpected(), key);
			}

		}
	}

	/**
	 * Update failing fields.
	 *
	 * @param inputData        the input data
	 * @param dbData           the db data
	 * @param comparisonResult the comparison result
	 * @throws IdRepoAppException the id repo app exception
	 */
	private void updateFailingFields(DocumentContext inputData, DocumentContext dbData, JSONCompareResult comparisonResult) {
		for (FieldComparisonFailure failure : comparisonResult.getFieldFailures()) {

			String path = StringUtils.substringBeforeLast(failure.getField(), DOT);
			if (StringUtils.contains(path, OPEN_SQUARE_BRACE)) {
				path = RegExUtils.replaceAll(path, "\\[", "\\[\\?\\(\\@\\.");
				path = RegExUtils.replaceAll(path, "=", "=='");
				path = RegExUtils.replaceAll(path, "\\]", "'\\)\\]");
			}

			String key = StringUtils.substringAfterLast(failure.getField(), DOT);
			if (StringUtils.isEmpty(key)) {
				key = failure.getField();
				path = ROOT;
			}

			if (failure.getExpected() instanceof JSONArray) {
				dbData.put(path, key, convertToObject(failure.getExpected().toString().getBytes(), List.class));
				inputData.put(path, key, convertToObject(failure.getExpected().toString().getBytes(), List.class));
			} else if (failure.getExpected() instanceof JSONObject) {
				Object object = convertToObject(failure.getExpected().toString().getBytes(), ObjectNode.class);
				dbData.put(path, key, object);
				inputData.put(path, key, object);
			} else {
				if (!failure.getExpected().toString().contentEquals("null")) {
					dbData.put(path, key, failure.getExpected());
					inputData.put(path, key, failure.getExpected());
				} else {
					inputData.put(path, key, failure.getActual());
				}
			}
		}
	}

	/**
	 * Update missing values.
	 *
	 * @param inputData        the input data
	 * @param dbData           the db data
	 * @param comparisonResult the comparison result
	 */
	@SuppressWarnings("unchecked")
	private void updateMissingValues(DocumentContext inputData, DocumentContext dbData, JSONCompareResult comparisonResult) {
		String path = StringUtils.substringBefore(comparisonResult.getMessage(), OPEN_SQUARE_BRACE);
		String key = StringUtils.substringAfterLast(path, DOT);
		path = StringUtils.substringBeforeLast(path, DOT);

		if (StringUtils.isEmpty(key)) {
			key = path;
			path = ROOT;
		}

		JsonPath jsonPath = JsonPath.compile(path + DOT + key);
		List<Map<String, String>> dbDataList = dbData.read(path + DOT + key, List.class);
		List<Map<String, String>> inputDataList = inputData.read(path + DOT + key, List.class);
		inputDataList.stream()
				.filter(map -> map.containsKey(LANGUAGE) && dbDataList.stream().filter(dbMap -> dbMap.containsKey(LANGUAGE))
						.allMatch(dbMap -> !StringUtils.equalsIgnoreCase(dbMap.get(LANGUAGE), map.get(LANGUAGE))))
				.forEach(value -> dbData.add(jsonPath, value));
		dbDataList.stream()
				.filter(map -> map.containsKey(LANGUAGE)
						&& inputDataList.stream().filter(inputDataMap -> inputDataMap.containsKey(LANGUAGE)).allMatch(
								inputDataMap -> !StringUtils.equalsIgnoreCase(inputDataMap.get(LANGUAGE), map.get(LANGUAGE))))
				.forEach(value -> inputData.add(jsonPath, value));
	}

	/**
	 * Update documents.
	 *
	 * @param uinHash    the uin hash
	 * @param uinObject  the uin object
	 * @param requestDTO the request DTO
	 * @throws IdRepoAppException the id repo app exception
	 */
	protected void updateDocuments(String uinHashwithSalt, Uin uinObject, RequestDTO requestDTO, boolean isDraft)
			throws IdRepoAppException {
		List<UinDocument> docList = new ArrayList<>();
		List<UinBiometric> bioList = new ArrayList<>();

		if (Objects.nonNull(uinObject.getBiometrics())) {
			updateCbeff(uinObject, requestDTO);
		}

		addDocuments(uinHashwithSalt, convertToBytes(requestDTO.getIdentity()), requestDTO.getDocuments(),
				uinObject.getUinRefId(), docList, bioList, isDraft);

		docList.stream().forEach(doc -> uinObject.getDocuments().stream()
				.filter(docObj -> StringUtils.equals(doc.getDoccatCode(), docObj.getDoccatCode())).forEach(docObj -> {
					docObj.setDocId(doc.getDocId());
					docObj.setDocName(doc.getDocName());
					docObj.setDoctypCode(doc.getDoctypCode());
					docObj.setDocfmtCode(doc.getDocfmtCode());
					docObj.setDocHash(doc.getDocHash());
					docObj.setUpdatedBy(IdRepoSecurityManager.getUser());
					docObj.setUpdatedDateTime(doc.getUpdatedDateTime());
				}));
		docList.stream()
				.filter(doc -> uinObject.getDocuments().stream()
						.allMatch(docObj -> !StringUtils.equals(doc.getDoccatCode(), docObj.getDoccatCode())))
				.forEach(doc -> uinObject.getDocuments().add(doc));
		bioList.stream()
				.forEach(bio -> uinObject.getBiometrics().stream()
						.filter(bioObj -> StringUtils.equals(bio.getBiometricFileType(), bioObj.getBiometricFileType()))
						.forEach(bioObj -> {
							bioObj.setBioFileId(bio.getBioFileId());
							bioObj.setBiometricFileName(bio.getBiometricFileName());
							bioObj.setBiometricFileHash(bio.getBiometricFileHash());
							bioObj.setUpdatedBy(IdRepoSecurityManager.getUser());
							bioObj.setUpdatedDateTime(bio.getUpdatedDateTime());
						}));
		bioList.stream()
				.filter(bio -> uinObject.getBiometrics().stream()
						.allMatch(bioObj -> !StringUtils.equals(bio.getBioFileId(), bioObj.getBioFileId())))
				.forEach(bio -> uinObject.getBiometrics().add(bio));
	}

	/**
	 * Update cbeff.
	 *
	 * @param uinObject  the uin object
	 * @param requestDTO the request DTO
	 * @throws IdRepoAppException the id repo app exception
	 */
	private void updateCbeff(Uin uinObject, RequestDTO requestDTO) {
		ObjectNode identityMap = convertToObject(uinObject.getUinData(), ObjectNode.class);
		IntStream.range(0, uinObject.getBiometrics().size()).forEach(index -> {
			UinBiometric bio = uinObject.getBiometrics().get(index);
			requestDTO.getDocuments().stream()
					.filter(doc -> StringUtils.equals(bio.getBiometricFileType(), doc.getCategory())).forEach(doc -> {
						try {
							String uinHash = uinObject.getUinHash().split("_")[1];
							String bioFileId = bio.getBioFileId();
							byte[] data = objectStoreHelper.getBiometricObject(uinHash, bioFileId);
								if (StringUtils.equalsIgnoreCase(
										identityMap.get(bio.getBiometricFileType()).get(FILE_FORMAT_ATTRIBUTE).asText(), CBEFF_FORMAT)
										&& bioFileId.endsWith(CBEFF_FORMAT)) {
									byte[] decodedBioData = CryptoUtil.decodeURLSafeBase64(doc.getValue());
									anonymousProfileHelper.setOldCbeff(CryptoUtil.encodeToURLSafeBase64(data));
									doc.setValue(CryptoUtil.encodeToURLSafeBase64(this.updateXML(decodedBioData, data)));
								}
						} catch (IdRepoAppUncheckedException e) {
							mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, "updateCbeff",
									ExceptionUtils.getStackTrace(e));
							throw new IdRepoAppUncheckedException(e.getErrorCode(), e.getErrorText(), e);
						} catch (Exception e) {
							mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, "updateCbeff",
									ExceptionUtils.getStackTrace(e));
							throw new IdRepoAppUncheckedException(INVALID_INPUT_PARAMETER.getErrorCode(),
									String.format(INVALID_INPUT_PARAMETER.getErrorMessage(), "documents/" + index + "/value"));
						}
					});
		});
	}

	private byte[] updateXML(byte[] inputBioData, byte[] existingBioData) throws Exception {
		List<BIR> existingBIRData = cbeffUtil.getBIRDataFromXML(existingBioData);
		List<BIR> inputBIRData = cbeffUtil.getBIRDataFromXML(inputBioData);
		Map<String, BIR> inputBIRDataMap = inputBIRData.stream()
				.collect(Collectors.toMap(bir -> bir.getBdbInfo().getType().stream().map(BiometricType::value)
						.collect(Collectors.joining())
						.concat(bir.getBdbInfo().getSubtype().stream().collect(Collectors.joining())), bir -> bir));
		Map<String, BIR> existingBIRDataMap = existingBIRData.stream()
				.collect(Collectors.toMap(bir -> bir.getBdbInfo().getType().stream().map(BiometricType::value)
						.collect(Collectors.joining())
						.concat(bir.getBdbInfo().getSubtype().stream().collect(Collectors.joining())), bir -> bir));
		inputBIRDataMap.entrySet().forEach(entry -> existingBIRDataMap.replace(entry.getKey(), entry.getValue()));
		byte[] updatedCbeff = cbeffUtil.createXML(new ArrayList<>(existingBIRDataMap.values()));
		anonymousProfileHelper.setNewCbeff(CryptoUtil.encodeToURLSafeBase64(updatedCbeff));
		return updatedCbeff;
	}

	private void issueCredential(String enryptedUin, String uinHash, String uinStatus, LocalDateTime expiryTimestamp) {
		List<CredentialRequestStatus> credStatusList = credRequestRepo.findByIndividualIdHash(uinHash);
		if (credStatusList.isEmpty() && uinStatus.contentEquals(activeStatus)) {
			CredentialRequestStatus credStatus = new CredentialRequestStatus();
			credStatus.setIndividualId(enryptedUin);
			credStatus.setIndividualIdHash(uinHash);
			credStatus.setPartnerId(dummyPartner.getDummyOLVPartnerId());
			credStatus.setStatus(CredentialRequestStatusLifecycle.NEW.toString());
			credStatus.setIdExpiryTimestamp(uinStatus.contentEquals(activeStatus) ? null : expiryTimestamp);
			credStatus.setCreatedBy(IdRepoSecurityManager.getUser());
			credStatus.setCrDTimes(DateUtils.getUTCCurrentDateTime());
			credRequestRepo.save(credStatus);
		} else if (!credStatusList.isEmpty() && uinStatus.contentEquals(activeStatus)) {
			credStatusList.forEach(credStatus -> {
				credStatus.setStatus(CredentialRequestStatusLifecycle.NEW.toString());
				credStatus.setUpdatedBy(IdRepoSecurityManager.getUser());
				credStatus.setUpdDTimes(DateUtils.getUTCCurrentDateTime());
				credRequestRepo.save(credStatus);
			});
		} else if (!credStatusList.isEmpty() && !uinStatus.contentEquals(activeStatus)) {
			credStatusList.forEach(credStatus -> {
				credStatus.setStatus(CredentialRequestStatusLifecycle.DELETED.toString());
				credStatus.setUpdatedBy(IdRepoSecurityManager.getUser());
				credStatus.setUpdDTimes(DateUtils.getUTCCurrentDateTime());
				credRequestRepo.save(credStatus);
			});
		}
	}

	/**
	 * Convert to object.
	 *
	 * @param identity the identity
	 * @param clazz    the clazz
	 * @return the object
	 * @throws IdRepoAppException the id repo app exception
	 */
	protected <T> T convertToObject(byte[] identity, Class<T> clazz) {
		try {
			return mapper.readValue(identity, clazz);
		} catch (IOException e) {
			mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, "convertToObject", e.getMessage());
			throw new IdRepoAppUncheckedException(ID_OBJECT_PROCESSING_FAILED, e);
		}
	}

	/**
	 * Convert to bytes.
	 *
	 * @param identity the identity
	 * @return the byte[]
	 * @throws IdRepoAppException the id repo app exception
	 */
	protected byte[] convertToBytes(Object identity) throws IdRepoAppException {
		try {
			return mapper.writeValueAsBytes(identity);
		} catch (JsonProcessingException e) {
			mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_SERVICE_IMPL, "convertToBytes", e.getMessage());
			throw new IdRepoAppException(ID_OBJECT_PROCESSING_FAILED, e);
		}
	}

}