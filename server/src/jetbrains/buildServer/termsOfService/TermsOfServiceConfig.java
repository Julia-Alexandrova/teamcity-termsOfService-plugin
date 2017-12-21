package jetbrains.buildServer.termsOfService;

import jetbrains.buildServer.configuration.FileWatcher;
import jetbrains.buildServer.serverSide.BuildServerAdapter;
import jetbrains.buildServer.serverSide.BuildServerListener;
import jetbrains.buildServer.serverSide.ServerPaths;
import jetbrains.buildServer.serverSide.TeamCityProperties;
import jetbrains.buildServer.serverSide.impl.FileWatcherFactory;
import jetbrains.buildServer.util.*;
import org.jdom.Attribute;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.intellij.openapi.util.text.StringUtil.isNotEmpty;
import static java.util.Collections.emptyMap;

@ThreadSafe
public class TermsOfServiceConfig {
    private static final String CONFIG_FILE = "terms-of-service-config.xml";

    private final List<AgreementSettings> myAgreements = new ArrayList<>();
    private Optional<GuestNoticeSettings> myGuestNotice = Optional.empty();

    private final File myConfigDir;
    private final File mySettingsFile;

    public TermsOfServiceConfig(@NotNull EventDispatcher<BuildServerListener> myEvents,
                                @NotNull ServerPaths serverPaths,
                                @NotNull FileWatcherFactory fileWatcherFactory) {
        myConfigDir = new File(serverPaths.getConfigDir(), "termsOfService");
        mySettingsFile = new File(myConfigDir, CONFIG_FILE);

        int watchInterval = TeamCityProperties.getInteger("teamcity.termsOfService.configWatchInterval", 10000);
        FileWatcher filesWatcher = fileWatcherFactory.createSingleFilesWatcher(mySettingsFile, watchInterval);

        filesWatcher.registerListener(requestor -> loadSettings());
        myEvents.addListener(new BuildServerAdapter() {
            @Override
            public void serverStartup() {
                loadSettings();
                filesWatcher.start();
            }

            @Override
            public void serverShutdown() {
                filesWatcher.stop();
            }
        });
    }

    @NotNull
    public synchronized List<AgreementSettings> getAgreements() {
        return new ArrayList<>(myAgreements);
    }

    @NotNull
    public synchronized Optional<AgreementSettings> getAgreement(@NotNull String id) {
        return myAgreements.stream().filter(a -> a.getId().equals(id)).findFirst();
    }

    @NotNull
    public synchronized Optional<GuestNoticeSettings> getGuestNotice() {
        return myGuestNotice;
    }

    synchronized void loadSettings() {
        try {
            if (mySettingsFile.exists()) {
                myAgreements.clear();
                Element parsed = FileUtil.parseDocument(mySettingsFile, false);
                List agreementsEls = parsed.getChildren("agreement");

                if (agreementsEls.isEmpty()) {
                    TermsOfServiceLogger.LOGGER.debug("No 'agreement' elements are found in " + FileUtil.getCanonicalFile(mySettingsFile).getPath());
                }

                for (Object agreementEl : agreementsEls) {
                    Element paramsElement = ((Element) agreementEl).getChild("parameters");
                    Map<String, String> params = paramsElement == null ? emptyMap() : XmlUtil.readParameters(paramsElement);
                    if (params.get("agreement-file") != null || params.get("agreement-link") != null) {
                        List<TermsOfServiceManager.Consent> consents = new ArrayList<>();
                        Element consentsEl = ((Element) agreementEl).getChild("consents");
                        if (consentsEl != null) {
                            for (Object consent : consentsEl.getChildren("consent")) {
                                Element consentEl = ((Element) consent);
                                String id = consentEl.getAttributeValue("id");
                                String text = consentEl.getAttributeValue("text");
                                boolean checked = Boolean.parseBoolean(consentEl.getAttributeValue("checked"));
                                if (isNotEmpty(id) && isNotEmpty(text)) {
                                    consents.add(new ConsentSettings(id, text, checked));
                                }
                            }
                        }
                        AgreementSettings agreementSettings = new AgreementSettings(((Element) agreementEl).getAttributeValue("id"), params, consents);
                        myAgreements.add(agreementSettings);
                    }
                }


                GuestNoticeSettings guestNoticeSettings = null;
                Element guestNoticeEl = parsed.getChild("guest-notice");
                if (guestNoticeEl != null) {
                    Element paramsElement = guestNoticeEl.getChild("parameters");
                    Map<String, String> params = paramsElement == null ? emptyMap() : XmlUtil.readParameters(paramsElement);
                    if (params.get("agreement") != null && params.get("text") != null) {
                        guestNoticeSettings = new GuestNoticeSettings(params.get("text"), params.get("agreement"));
                    }
                }
                myGuestNotice = Optional.ofNullable(guestNoticeSettings);

            }

            if (myAgreements.isEmpty()) {
                TermsOfServiceLogger.LOGGER.warn("No Terms of Service rules were found in " + FileUtil.getCanonicalFile(mySettingsFile).getPath());
            }
        } catch (IOException | JDOMException e) {
            TermsOfServiceLogger.LOGGER.warnAndDebugDetails("Error while loading Terms Of Service settings from " + FileUtil.getCanonicalFile(mySettingsFile).getPath(), e);
        }
    }

    class AgreementSettings {

        private final String id;
        private final Map<String, String> params;
        private final List<TermsOfServiceManager.Consent> consents;

        AgreementSettings(@NotNull String id, @NotNull Map<String, String> params, List<TermsOfServiceManager.Consent> consents) {
            this.id = id;
            this.params = params;
            this.consents = consents;
        }

        @NotNull
        public String getId() {
            return id;
        }

        @NotNull
        public String getVersion() {
            return params.getOrDefault("version", "1");
        }

        @NotNull
        public String getShortName() {
            return StringUtil.notNullize(params.get("short-name"), "Terms of Service");
        }

        @NotNull
        public String getFullName() {
            return StringUtil.notNullize(params.get("full-name"), "Terms of Service");
        }

        @Nullable
        public String getText() {
            String agreementFileParam = params.get("agreement-file");
            if (agreementFileParam == null) {
                return null;
            }
            File agreementFile = new File(myConfigDir, agreementFileParam);
            try {
                return FileUtil.readText(agreementFile, "UTF-8");
            } catch (IOException e) {
                TermsOfServiceLogger.LOGGER.warnAndDebugDetails("Error while reading Terms Of Service agreement file from " + agreementFile, e);
                throw new IllegalStateException("Error while reading Terms Of Service agreement file from " + agreementFile, e);
            }
        }

        @Nullable
        public String getLink() {
            if (getText() != null) return null;
            return params.get("agreement-link");
        }

        public boolean getForceAccept() {
            return Boolean.parseBoolean(params.getOrDefault("force-accept", "true"));
        }

        @NotNull
        public List<TermsOfServiceManager.Consent> getConsents() {
            return consents;
        }
    }

    class GuestNoticeSettings {
        private final String text;
        private final String agreementId;

        GuestNoticeSettings(String text, String agreementId) {
            this.text = text;
            this.agreementId = agreementId;
        }

        public String getText() {
            return text;
        }

        public String getAgreementId() {
            return agreementId;
        }
    }

    class ConsentSettings implements TermsOfServiceManager.Consent {
        @NotNull private final String id;
        @NotNull private final String text;
        private final boolean checked;

        ConsentSettings(@NotNull String id, @NotNull String text, boolean checked) {
            this.id = id;
            this.text = text;
            this.checked = checked;
        }

        @NotNull
        @Override
        public String getId() {
            return id;
        }

        @Override
        public boolean isCheckedByDefault() {
            return checked;
        }

        @NotNull
        @Override
        public String getText() {
            return text;
        }
    }
}

