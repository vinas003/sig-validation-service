package se.idsec.sigval.sigvalservice.configuration;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import se.idsec.signservice.security.sign.pdf.configuration.PDFAlgorithmRegistry;
import se.swedenconnect.sigval.commons.algorithms.JWSAlgorithmRegistry;
import se.swedenconnect.sigval.commons.algorithms.PublicKeyType;
import se.swedenconnect.sigval.commons.timestamp.TimeStampPolicyVerifier;
import se.swedenconnect.sigval.commons.timestamp.impl.BasicTimstampPolicyVerifier;
import se.swedenconnect.sigval.commons.utils.GeneralCMSUtils;
import se.swedenconnect.sigval.pdf.pdfstruct.impl.DefaultPDFSignatureContextFactory;
import se.swedenconnect.sigval.pdf.svt.PDFSVTSigValClaimsIssuer;
import se.swedenconnect.sigval.pdf.svt.PDFSVTValidator;
import se.swedenconnect.sigval.pdf.timestamp.issue.impl.DefaultPDFDocTimestampSignatureInterface;
import se.swedenconnect.sigval.pdf.verify.ExtendedPDFSignatureValidator;
import se.swedenconnect.sigval.pdf.verify.PDFSingleSignatureValidator;
import se.swedenconnect.sigval.pdf.verify.impl.PDFSingleSignatureValidatorImpl;
import se.swedenconnect.sigval.pdf.verify.impl.SVTenabledPDFDocumentSigVerifier;
import se.swedenconnect.sigval.pdf.verify.policy.PDFSignaturePolicyValidator;
import se.swedenconnect.sigval.pdf.verify.policy.impl.PkixPdfSignaturePolicyValidator;
import se.idsec.sigval.sigvalservice.configuration.keys.LocalKeySource;
import se.swedenconnect.sigval.svt.algorithms.SVTAlgoRegistry;
import se.swedenconnect.sigval.xml.policy.XMLSignaturePolicyValidator;
import se.swedenconnect.sigval.xml.policy.impl.PkixXmlSignaturePolicyValidator;
import se.swedenconnect.sigval.xml.svt.XMLDocumentSVTIssuer;
import se.swedenconnect.sigval.xml.svt.XMLSVTSigValClaimsIssuer;
import se.swedenconnect.sigval.xml.svt.XMLSVTValidator;
import se.swedenconnect.sigval.xml.verify.ExtendedXMLSignedDocumentValidator;
import se.swedenconnect.sigval.xml.verify.XMLSignatureElementValidator;
import se.swedenconnect.sigval.xml.verify.impl.XMLSignatureElementValidatorImpl;
import se.swedenconnect.sigval.xml.verify.impl.XMLSignedDocumentValidator;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.Objects;

@Component
@Slf4j
public class SignatureValidatorProvider {

  private final CertificateValidators certValidators;
  private final LocalKeySource svtKeySource;

  @Value("${sigval-service.svt.model.sig-algo}") String svtSigAlgo;
  @Value("${sigval-service.svt.timestamp.policy:#{null}}") String timestampPolicy;
  @Value("${sigval-service.svt.validator-enabled}") boolean enableSvtValidation;
  @Value("${sigval-service.validator.strict-pdf-context}") boolean strictPdfContextFactory;

  @Getter private DefaultPDFDocTimestampSignatureInterface svtTsSigner;
  @Getter private PDFSVTSigValClaimsIssuer pdfsvtSigValClaimsIssuer;
  @Getter private ExtendedPDFSignatureValidator pdfSignatureValidator;
  @Getter private XMLDocumentSVTIssuer xmlDocumentSVTIssuer;
  @Getter private ExtendedXMLSignedDocumentValidator xmlSignedDocumentValidator;
  @Getter private XMLSignatureElementValidator xmlSignatureElementValidator;

  private TimeStampPolicyVerifier timeStampPolicyVerifier;
  private JWSAlgorithm svtJWSAlgorithm;


  @Autowired
  public SignatureValidatorProvider(CertificateValidators certValidators, LocalKeySource svtKeySource) {
    this.certValidators = certValidators;
    this.svtKeySource = svtKeySource;
  }

  public void loadValidators() throws JOSEException, NoSuchAlgorithmException, IOException, CertificateException {
    certValidators.loadValidators();
    svtJWSAlgorithm = jwsAlgorithm();
    timeStampPolicyVerifier = timeStampPolicyVerifier();
    xmlSignatureElementValidator = xmlSignatureElementValidator();
    xmlSignedDocumentValidator = xmlSignedDocumentValidator();
    xmlDocumentSVTIssuer = xmlDocumentSVTIssuer();
    pdfSignatureValidator = pdfSignatureValidator();
    pdfsvtSigValClaimsIssuer = pdfsvtSigValClaimsIssuer();
    svtTsSigner = svtTsSigner();
  }

  private DefaultPDFDocTimestampSignatureInterface svtTsSigner() {
    DefaultPDFDocTimestampSignatureInterface timeStampSigner = new DefaultPDFDocTimestampSignatureInterface(
      svtKeySource.getCredential().getPrivateKey(),
      Collections.singletonList(svtKeySource.getCredential().getEntityCertificate()),
      SVTAlgoRegistry.getAlgoParams(svtJWSAlgorithm).getSigAlgoId());
    if (StringUtils.isNotEmpty(timestampPolicy)){
      timeStampSigner.setTimeStampPolicyOid(new ASN1ObjectIdentifier(timestampPolicy));
    }
    return timeStampSigner;
  }

  PDFSVTSigValClaimsIssuer pdfsvtSigValClaimsIssuer() throws NoSuchAlgorithmException, JOSEException {
    return new PDFSVTSigValClaimsIssuer(
      svtJWSAlgorithm,
      Objects.requireNonNull(svtKeySource.getCredential().getPrivateKey()),
      Collections.singletonList(svtKeySource.getCredential().getEntityCertificate()),
      pdfSignatureValidator);
  }


  private ExtendedPDFSignatureValidator pdfSignatureValidator() {
    PDFSignaturePolicyValidator signaturePolicyValidator = new PkixPdfSignaturePolicyValidator();
    PDFSingleSignatureValidator pdfSignatureVerifier = new PDFSingleSignatureValidatorImpl(
      certValidators.getSignatureCertificateValidator(), signaturePolicyValidator,
      timeStampPolicyVerifier);

    // Setup SVA validator
    PDFSVTValidator pdfsvtValidator = new PDFSVTValidator(certValidators.getSvtCertificateValidator(), timeStampPolicyVerifier);

    DefaultPDFSignatureContextFactory pdfContextFactory = new DefaultPDFSignatureContextFactory();
    pdfContextFactory.setStrict(strictPdfContextFactory);

    // Get the pdf validator
    return new SVTenabledPDFDocumentSigVerifier(
      pdfSignatureVerifier,
      enableSvtValidation ? pdfsvtValidator : null,
      pdfContextFactory);
  }


  public XMLDocumentSVTIssuer xmlDocumentSVTIssuer() throws JOSEException, NoSuchAlgorithmException {
    XMLSVTSigValClaimsIssuer claimsIssuer = new XMLSVTSigValClaimsIssuer(
      svtJWSAlgorithm,
      Objects.requireNonNull(svtKeySource.getCredential().getPrivateKey()),
      Collections.singletonList(svtKeySource.getCredential().getEntityCertificate()),
      xmlSignatureElementValidator
    );
    return new XMLDocumentSVTIssuer(claimsIssuer);
  }

  private JWSAlgorithm jwsAlgorithm() throws IOException, NoSuchAlgorithmException {
    JWSAlgorithm svtJWSSigAlgorithm = JWSAlgorithmRegistry.get(svtSigAlgo);
    SVTAlgoRegistry.AlgoProperties svtAlgoParams = SVTAlgoRegistry.getAlgoParams(svtJWSSigAlgorithm);
    PublicKeyType pkType = GeneralCMSUtils.getPkParams(svtKeySource.getCertificate().getPublicKey()).getPkType();

    // Check consistency with SVT key type
    switch (pkType){
    case EC:
      if (svtAlgoParams.getType().equals(JWSAlgorithm.Family.EC)) return svtJWSSigAlgorithm;
      break;
    case RSA:
      if (svtAlgoParams.getType().equals(JWSAlgorithm.Family.RSA)) return svtJWSSigAlgorithm;
    }

    throw new NoSuchAlgorithmException("The selected algorithm does not match the provided SVT signing key");
  }


  public ExtendedXMLSignedDocumentValidator xmlSignedDocumentValidator() {
    return new XMLSignedDocumentValidator(xmlSignatureElementValidator);
  }

  private XMLSignatureElementValidator xmlSignatureElementValidator(){
    XMLSignaturePolicyValidator xmlSignaturePolicyValidator = new PkixXmlSignaturePolicyValidator();

    return new XMLSignatureElementValidatorImpl(
      certValidators.getSignatureCertificateValidator(),
      xmlSignaturePolicyValidator,
      timeStampPolicyVerifier,
      enableSvtValidation ? new XMLSVTValidator(certValidators.getSvtCertificateValidator(), certValidators.getKidMatchCerts()) : null
    );
  }

  private TimeStampPolicyVerifier timeStampPolicyVerifier() {
    return new BasicTimstampPolicyVerifier(certValidators.getTimestampCertificateValidator());
  }

}
