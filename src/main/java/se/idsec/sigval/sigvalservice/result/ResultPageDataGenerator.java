package se.idsec.sigval.sigvalservice.result;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.cert.X509CertificateHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import se.idsec.signservice.security.sign.SignatureValidationResult;
import se.swedenconnect.sigval.commons.data.ExtendedSigValResult;
import se.swedenconnect.sigval.commons.data.SigValIdentifiers;
import se.swedenconnect.sigval.commons.data.SignedDocumentValidationResult;
import se.swedenconnect.sigval.commons.data.TimeValidationResult;
import se.swedenconnect.sigval.pdf.data.ExtendedPdfSigValResult;
import se.idsec.sigval.sigvalservice.configuration.ui.UIText;
import se.idsec.sigval.sigvalservice.configuration.ui.UIUtils;
import se.idsec.sigval.sigvalservice.result.cert.CertUtils;
import se.idsec.sigval.sigvalservice.result.cert.SubjectDnAttribute;
import se.idsec.sigval.sigvalservice.result.data.*;
import se.swedenconnect.sigval.svt.claims.PolicyValidationClaims;
import se.swedenconnect.sigval.svt.claims.ValidationConclusion;
import se.swedenconnect.sigval.xml.data.ExtendedXmlSigvalResult;
import se.swedenconnect.cert.extensions.AuthnContext;
import se.swedenconnect.schemas.cert.authcont.saci_1_0.AttributeMapping;
import se.swedenconnect.schemas.cert.authcont.saci_1_0.AuthContextInfo;
import se.swedenconnect.schemas.cert.authcont.saci_1_0.SAMLAuthContext;
import se.swedenconnect.schemas.saml_2_0.assertion.Attribute;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
public class ResultPageDataGenerator {

  private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm z");

  private final UIText uiText;

  @Autowired
  public ResultPageDataGenerator(UIText uiText) {
    this.uiText = uiText;
  }

  public ResultPageData getResultPageData(SignedDocumentValidationResult sigValResult, String documentName, String documentType, String lang) {

    ResultPageData.ResultPageDataBuilder rpdBuilder = ResultPageData.builder();
    rpdBuilder
      .documentName(documentName)
      .documentType(documentType)
      .numberOfSignatures(sigValResult.getSignatureCount());

    if (sigValResult.getSignatureCount() == 0) {
      rpdBuilder.status(DocValidStatus.unsigned);
      return rpdBuilder.build();
    }

    return generateResultPgeData(sigValResult, rpdBuilder.build(), lang);
  }

  private ResultPageData generateResultPgeData(SignedDocumentValidationResult sigValResult, ResultPageData resultPageData, String lang) {

    List<ExtendedSigValResult> signatureValidationResults = sigValResult.getSignatureValidationResults();

    List<ResultSignatureData> signatureData = signatureValidationResults.stream()
      .map(signatureValResult -> getSignatureResult(signatureValResult, lang))
      .collect(Collectors.toList());
    resultPageData.setResultSignatureDataList(signatureData);

    boolean oneValidSigCoversAlldata = signatureData.stream()
      .filter(resultSignatureData -> resultSignatureData.getStatus().equals(SigValidStatus.ok))
      .filter(resultSignatureData -> resultSignatureData.isCoversAllData())
      .findFirst().isPresent();
    List<ResultSignatureData> validSignatures = signatureData.stream()
      .filter(resultSignatureData -> resultSignatureData.getStatus().equals(SigValidStatus.ok))
      .collect(Collectors.toList());
    int validSigCount = validSignatures.size();
    resultPageData.setValidSignatures(validSigCount);
    if (validSigCount > 0 && oneValidSigCoversAlldata) {
      if (validSigCount == signatureValidationResults.size()) {
        resultPageData.setStatus(DocValidStatus.ok);
      }
      else {
        resultPageData.setStatus(DocValidStatus.someinvalid);
      }
    }
    else {
      // No valid signature, or no valid signature coveres doc. Determine which
      if (validSigCount > 0) {
        // There is a valid signature, but no valid signature covers the whole document
        resultPageData.setStatus(DocValidStatus.novalidcoversdoc);
      }
      else {
        // There is no valid signature
        resultPageData.setStatus(DocValidStatus.invalid);
      }
    }

    return resultPageData;
  }

  private ResultSignatureData getSignatureResult(ExtendedSigValResult signatureValResult, String lang) {
    ResultSignatureData.ResultSignatureDataBuilder builder = ResultSignatureData.builder();

    // Set all generic data
    builder.coversAllData(signatureValResult.isCoversDocument());
    builder.svt(signatureValResult.getSvtJWT() != null);
    builder.signedDataAvailable(signatureValResult.getSignedDocument() != null);
    if (signatureValResult.getException() != null) builder.errorMessage(signatureValResult.getException().getMessage());
    SignatureValidationResult.Status status = signatureValResult.getStatus();
    switch (status) {

    case SUCCESS:
      builder.status(SigValidStatus.ok);
      builder.validationDateLimit(getValidationDateLimit(signatureValResult));
      break;
    case INTERDETERMINE:
      builder.status(SigValidStatus.incomplete);
      break;
    case ERROR_INVALID_SIGNATURE:
    case ERROR_SIGNER_INVALID:
    case ERROR_SIGNER_NOT_ACCEPTED:
    case ERROR_NOT_TRUSTED:
    case ERROR_BAD_FORMAT:
      builder.status(SigValidStatus.sigerror);
    }

    //Set timestamp
    addTimeStamptime(signatureValResult, builder);

    //Set certificate data
    X509Certificate signerCertificate = signatureValResult.getSignerCertificate();
    setSignerCertData(signerCertificate, builder, lang);

    if (signatureValResult instanceof ExtendedXmlSigvalResult)
      return getXmlSigResult(
        (ExtendedXmlSigvalResult) signatureValResult, builder.build());
    if (signatureValResult instanceof ExtendedPdfSigValResult)
      return getPdfSigResult(
        (ExtendedPdfSigValResult) signatureValResult, builder.build());
    return builder.build();
  }

  private String getValidationDateLimit(ExtendedSigValResult signatureValResult) {
    try {
      if (signatureValResult.getSvtJWT() != null){
        // This is SVT. Use the SVT expiry date if possible
        if (signatureValResult.getSvtJWT().getJWTClaimsSet().getExpirationTime() != null){
          return dateFormat.format(signatureValResult.getSvtJWT().getJWTClaimsSet().getExpirationTime());
        } else {
          return null;
        }
      }

      // Not SVT. Examine cert expiry time
      Date mostRecentExpiryDate = null;
      boolean foundExpiryDate = false;
      List<X509Certificate> validatedCertificatePath = signatureValResult.getCertificateValidationResult().getValidatedCertificatePath();
      // We need at least 2 certs. If it is one, then trust is direct TA trust and expiry date does not matter.
      if (validatedCertificatePath.size() < 2) return null;

      for (int i = 0; i<validatedCertificatePath.size()-1 ; i++){
        X509Certificate cert = validatedCertificatePath.get(i);
        Date notAfter = cert.getNotAfter();
        if (foundExpiryDate){
          mostRecentExpiryDate = notAfter.before(mostRecentExpiryDate) ? notAfter : mostRecentExpiryDate;
        } else {
          mostRecentExpiryDate = notAfter;
        }
        foundExpiryDate = true;
      }
      if (foundExpiryDate) {
        return dateFormat.format(mostRecentExpiryDate);
      }

    } catch (Exception ex){
      log.warn("Error parsing expiry dates: {}", ex.getMessage());
    }
    return null;
  }

  private void addTimeStamptime(ExtendedSigValResult signatureValResult,
    ResultSignatureData.ResultSignatureDataBuilder builder) {
    List<TimeValidationResult> timeValidationResults = signatureValResult.getTimeValidationResults();
    if (timeValidationResults == null || timeValidationResults.isEmpty()) return;
    Date eariestTime = new Date();
    boolean foundValidTime = false;
    String type = null;
    for (TimeValidationResult tsResult: timeValidationResults){
      try {
        List<PolicyValidationClaims> validationClaims = tsResult.getTimeValidationClaims().getVal();
        boolean valid = validationClaims.stream()
          .anyMatch(policyValidationClaims -> policyValidationClaims.getRes().equals(ValidationConclusion.PASSED));
        if (valid) {
          foundValidTime = true;
          Date tsTime = new Date(tsResult.getTimeValidationClaims().getTime() > Long.parseLong("99999999999")
            ? tsResult.getTimeValidationClaims().getTime()
            : tsResult.getTimeValidationClaims().getTime() * 1000);
          if (tsTime.before(eariestTime)){
            eariestTime = tsTime;
            type = getTypeId(tsResult.getTimeValidationClaims().getType());
          }
        }
      } catch (Exception ex) {
        log.error("failed to parse signature timestamp data");
      }
    }
    if (foundValidTime) {
      builder.timeStampTime(dateFormat.format(eariestTime));
      builder.timeStampType(type);
    }
  }

  private String getTypeId(String type) {
    switch (type) {
    case SigValIdentifiers.TIME_VERIFICATION_TYPE_PDF_DOC_TIMESTAMP:
      return "docTimestamp";
    case SigValIdentifiers.TIME_VERIFICATION_TYPE_SIG_TIMESTAMP:
      return "sigTimestamp";
    case SigValIdentifiers.TIME_VERIFICATION_TYPE_SVT:
      return "svt";
    }
    return null;
  }

  private void setSignerCertData(X509Certificate signerCertificate, ResultSignatureData.ResultSignatureDataBuilder builder, String lang) {
    try {
      X509CertificateHolder certificateHolder = new X509CertificateHolder(signerCertificate.getEncoded());
      AuthnContext authnContext = AuthnContext.fromExtensions(certificateHolder.getExtensions());
      SAMLAuthContext authContextExtData = authnContext.getStatementInfoList().get(0);
      AuthContextInfo authContextInfo = authContextExtData.getAuthContextInfo();
      builder
        .assertionRef(authContextInfo.getAssertionRef())
        .idp(authContextInfo.getIdentityProvider())
        .loa(authContextInfo.getAuthnContextClassRef())
        .signingTime(dateFormat.format(authContextInfo.getAuthenticationInstant().toGregorianCalendar().getTime()))
        .serviceProvider(authContextInfo.getServiceID());
      List<DisplayAttribute> displayAttributes = getAttrsFromAuthContextExt(authContextExtData, lang);
      builder.signerAttribute(displayAttributes);
      return;
    }
    catch (Exception ignored) {
      // This just means that the signing certificate did not contain any auth context extension
    }
    List<DisplayAttribute> displayAttributes = getAttrsFromSubjectField(signerCertificate, lang);
    builder.signerAttribute(displayAttributes);
  }

  private List<DisplayAttribute> getAttrsFromAuthContextExt(SAMLAuthContext authContextExtData, String lang) {
    if (authContextExtData == null) return new ArrayList<>();
    try {
      List<AttributeMapping> attributeMappings = authContextExtData.getIdAttributes().getAttributeMappings();
      return attributeMappings.stream()
        .map(attributeMapping -> {
          Attribute attribute = attributeMapping.getAttribute();
          SamlAttribute samlAttr = SamlAttribute.getAttributeFromSamlName(attribute.getName());
          String attributeValue = new StringBuilder().append(attribute.getAttributeValues().get(0)).toString();
          return new DisplayAttribute(UIUtils.fromIso(
            UIUtils.fromUtf(uiText.getBundle(UIText.UiBundle.samlAttr, lang).getString(samlAttr.name()))), attributeValue, samlAttr.getDisplayOrder());
        })
        .sorted(Comparator.comparingInt(DisplayAttribute::getOrder))
        .collect(Collectors.toList());
    }
    catch (Exception ex) {
      log.error("Unable to parse subject name from authContextExtension", ex);
      return new ArrayList<>();
    }
  }

  private List<DisplayAttribute> getAttrsFromSubjectField(X509Certificate signCert, String lang) {
    if (signCert == null) return new ArrayList<>();
    try {
      Map<SubjectDnAttribute, String> subjectAttributes = CertUtils.getSubjectAttributes(signCert);
      return subjectAttributes.keySet().stream()
        .map(subjectDnAttribute -> {
          String name = subjectDnAttribute.equals(SubjectDnAttribute.unknown)
            ? subjectDnAttribute.getOid()
            : uiText.getBundle(UIText.UiBundle.x509Attr, lang).getString(subjectDnAttribute.name());
          return new DisplayAttribute(name,subjectAttributes.get(subjectDnAttribute), subjectDnAttribute.getOrder());
        })
        .sorted(Comparator.comparingInt(DisplayAttribute::getOrder))
        .collect(Collectors.toList());
    } catch (IOException ex) {
      log.error("Unable to parse subject name from certificate", ex);
      return new ArrayList<>();
    }
  }

  private static ResultSignatureData getPdfSigResult(ExtendedPdfSigValResult signatureValResult,
    ResultSignatureData rsData) {
    return rsData;
  }

  private static ResultSignatureData getXmlSigResult(ExtendedXmlSigvalResult signatureValResult,
    ResultSignatureData rsData) {
    return rsData;
  }

}
