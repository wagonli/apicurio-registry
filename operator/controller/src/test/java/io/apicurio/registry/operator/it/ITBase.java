package io.apicurio.registry.operator.it;

import io.apicurio.registry.operator.App;
import io.apicurio.registry.operator.Constants;
import io.apicurio.registry.operator.OperatorException;
import io.apicurio.registry.operator.api.v1.ApicurioRegistry3;
import io.apicurio.registry.utils.Cell;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudget;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import jakarta.enterprise.inject.spi.CDI;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static io.apicurio.registry.utils.Cell.cell;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.eclipse.microprofile.config.ConfigProvider.getConfig;

public abstract class ITBase {

    private static final Logger log = LoggerFactory.getLogger(ITBase.class);

    public static final String DEPLOYMENT_TARGET = "test.operator.deployment-target";
    public static final String OPERATOR_DEPLOYMENT_PROP = "test.operator.deployment";
    public static final String INGRESS_HOST_PROP = "test.operator.ingress-host";
    public static final String INGRESS_SKIP_PROP = "test.operator.ingress-skip";
    public static final String CLEANUP = "test.operator.cleanup";
    public static final String CRD_FILE = "../model/target/classes/META-INF/fabric8/apicurioregistries3.registry.apicur.io-v1.yml";
    public static final String REMOTE_TESTS_INSTALL_FILE = "test.operator.install-file";

    public static final Duration POLL_INTERVAL_DURATION = ofSeconds(5);
    public static final Duration SHORT_DURATION = ofSeconds(30);
    // NOTE: When running remote tests, some extra time might be needed to pull an image before the pod can be run.
    // TODO: Consider changing the duration based on test type or the situation.
    public static final Duration MEDIUM_DURATION = ofSeconds(60);
    public static final Duration LONG_DURATION = ofSeconds(5 * 60);

    public enum OperatorDeployment {
        local, remote
    }

    protected static OperatorDeployment operatorDeployment;
    protected static KubernetesClient client;
    protected static PodLogManager podLogManager;
    protected static PortForwardManager portForwardManager;
    protected static IngressManager ingressManager;
    protected static String deploymentTarget;
    protected static String namespace;
    protected static boolean cleanup;
    protected static boolean strimziInstalled = false;
    private static App app;
    protected static JobManager jobManager;
    protected static HostAliasManager hostAliasManager;

    @BeforeAll
    public static void before() throws Exception {
        operatorDeployment = getConfig().getValue(OPERATOR_DEPLOYMENT_PROP,
                OperatorDeployment.class);
        deploymentTarget = getConfig().getValue(DEPLOYMENT_TARGET, String.class);
        cleanup = getConfig().getValue(CLEANUP, Boolean.class);

        setDefaultAwaitilityTimings();
        namespace = calculateNamespace();
        client = createK8sClient(namespace);
        createCRDs();
        createNamespace(client, namespace);

        portForwardManager = new PortForwardManager(client, namespace);
        ingressManager = new IngressManager(client, namespace);
        podLogManager = new PodLogManager(client);
        hostAliasManager = new HostAliasManager(client);
        jobManager = new JobManager(client, hostAliasManager);

        if (operatorDeployment == OperatorDeployment.remote) {
            createTestResources();
            startOperatorLogs();
        } else {
            startOperator();
        }
    }

    @BeforeEach
    public void beforeEach(TestInfo testInfo) {
        String testClassName = testInfo.getTestClass().map(c -> c.getSimpleName() + ".").orElse("");
        log.info("\n" +
                        "------- STARTING: {}{}\n" +
                        "------- Namespace: {}\n" +
                        "------- Mode: {}\n" +
                        "------- Deployment target: {}",
                testClassName, testInfo.getDisplayName(),
                namespace,
                ((operatorDeployment == OperatorDeployment.remote) ? "remote" : "local"),
                deploymentTarget);
    }

    protected static void checkDeploymentExists(ApicurioRegistry3 primary, String component, int replicas) {
        await().atMost(MEDIUM_DURATION).ignoreExceptions().untilAsserted(() -> {
            assertThat(client.apps().deployments()
                    .withName(primary.getMetadata().getName() + "-" + component + "-deployment").get()
                    .getStatus().getReadyReplicas()).isEqualTo(replicas);
        });
    }

    protected static void checkDeploymentDoesNotExist(ApicurioRegistry3 primary, String component) {
        await().atMost(SHORT_DURATION).ignoreExceptions().untilAsserted(() -> {
            assertThat(client.apps().deployments()
                    .withName(primary.getMetadata().getName() + "-" + component + "-deployment").get())
                    .isNull();
        });
    }

    protected static void checkServiceExists(ApicurioRegistry3 primary, String component) {
        await().atMost(SHORT_DURATION).ignoreExceptions().untilAsserted(() -> {
            assertThat(client.services()
                    .withName(primary.getMetadata().getName() + "-" + component + "-service").get())
                    .isNotNull();
        });
    }

    protected static void checkServiceDoesNotExist(ApicurioRegistry3 primary, String component) {
        await().atMost(SHORT_DURATION).ignoreExceptions().untilAsserted(() -> {
            assertThat(client.services()
                    .withName(primary.getMetadata().getName() + "-" + component + "-service").get()).isNull();
        });
    }

    protected static void checkIngressExists(ApicurioRegistry3 primary, String component) {
        await().atMost(SHORT_DURATION).ignoreExceptions().untilAsserted(() -> {
            assertThat(client.network().v1().ingresses()
                    .withName(primary.getMetadata().getName() + "-" + component + "-ingress").get())
                    .isNotNull();
        });
    }

    protected static void checkIngressDoesNotExist(ApicurioRegistry3 primary, String component) {
        await().atMost(SHORT_DURATION).ignoreExceptions().untilAsserted(() -> {
            assertThat(client.network().v1().ingresses()
                    .withName(primary.getMetadata().getName() + "-" + component + "-ingress").get()).isNull();
        });
    }

    /**
     * Update the Kubernetes resource, and retry if the update fails because the object has been modified on the server.
     * Use this method to make tests more resilient.
     *
     * @param resource Resource to be updated. The metadata must be set.
     * @param updater  Reentrant function that updates the resource in-place.
     * @return The resource after it has been updated.
     */
    protected static <T extends HasMetadata> T updateWithRetries(T resource, Consumer<T> updater) {
        var rval = cell(resource);
        await().atMost(SHORT_DURATION).until(() -> {
            try {
                var r = rval.get();
                r = client.resource(r).get();
                updater.accept(r);
                r = client.resource(r).update();
                rval.set(r);
                return true;
            } catch (KubernetesClientException ex) {
                if (ex.getMessage().contains("the object has been modified")) {
                    log.debug("Retrying:", ex);
                    return false;
                } else {
                    throw ex;
                }
            }
        });
        return rval.get();
    }

    protected static PodDisruptionBudget checkPodDisruptionBudgetExists(ApicurioRegistry3 primary,
                                                                        String component) {
        final Cell<PodDisruptionBudget> rval = cell();
        await().atMost(SHORT_DURATION).ignoreExceptions().untilAsserted(() -> {
            PodDisruptionBudget pdb = client.policy().v1().podDisruptionBudget()
                    .withName(primary.getMetadata().getName() + "-" + component + "-poddisruptionbudget")
                    .get();
            assertThat(pdb).isNotNull();
            rval.set(pdb);
        });

        return rval.get();
    }

    protected static NetworkPolicy checkNetworkPolicyExists(ApicurioRegistry3 primary, String component) {
        final Cell<NetworkPolicy> rval = cell();
        await().atMost(SHORT_DURATION).ignoreExceptions().untilAsserted(() -> {
            NetworkPolicy networkPolicy = client.network().v1().networkPolicies()
                    .withName(primary.getMetadata().getName() + "-" + component + "-networkpolicy").get();
            assertThat(networkPolicy).isNotNull();
            rval.set(networkPolicy);
        });

        return rval.get();
    }

    static KubernetesClient createK8sClient(String namespace) {
        return new KubernetesClientBuilder()
                .withConfig(new ConfigBuilder(Config.autoConfigure(null)).withNamespace(namespace).build())
                .build();
    }

    private static List<HasMetadata> loadTestResources() throws IOException {
        var installFilePath = Path
                .of(getConfig().getValue(REMOTE_TESTS_INSTALL_FILE, String.class));
        try {
            var installFileRaw = Files.readString(installFilePath);
            // We're not editing the deserialized resources to replicate the user experience
            installFileRaw = installFileRaw.replace("PLACEHOLDER_NAMESPACE", namespace);
            return Serialization.unmarshal(installFileRaw);
        } catch (NoSuchFileException ex) {
            throw new OperatorException("Remote tests require an install file to be generated. " +
                    "Please run `make INSTALL_FILE=controller/target/test-install.yaml dist-install-file` first, " +
                    "or see the README for more information.", ex);
        }
    }

    private static void createTestResources() throws Exception {
        log.info("Creating generated resources into Namespace {}", namespace);
        loadTestResources().forEach(r -> {
            if ("minikube".equals(deploymentTarget) && r instanceof Deployment d) {
                // See https://stackoverflow.com/a/46101923
                d.getSpec().getTemplate().getSpec().getContainers()
                        .forEach(c -> c.setImagePullPolicy("IfNotPresent"));
            }
            client.resource(r).inNamespace(namespace).createOrReplace();
        });
    }

    private static void startOperatorLogs() {
        List<Pod> operatorPods = new ArrayList<>();
        await().atMost(SHORT_DURATION).ignoreExceptions().untilAsserted(() -> {
            operatorPods.clear();
            operatorPods.addAll(client.pods()
                    .withLabels(Map.of(
                            "app.kubernetes.io/name", "apicurio-registry-operator",
                            "app.kubernetes.io/component", "operator",
                            "app.kubernetes.io/part-of", "apicurio-registry"))
                    .list().getItems());
            assertThat(operatorPods).hasSize(1);
        });
        podLogManager.startPodLog(ResourceID.fromResource(operatorPods.get(0)));
    }

    private static void cleanTestResources() throws Exception {
        if (cleanup) {
            log.info("Deleting generated resources from Namespace {}", namespace);
            loadTestResources().forEach(r -> {
                client.resource(r).inNamespace(namespace).delete();
            });
        }
    }

    private static void createCRDs() {
        log.info("Creating CRDs");
        try {
            var crd = client.load(new FileInputStream(CRD_FILE));
            crd.createOrReplace();
            await().ignoreExceptions().until(() -> {
                crd.resources().forEach(r -> assertThat(r.get()).isNotNull());
                return true;
            });
        } catch (Exception e) {
            log.warn("Failed to create the CRD, retrying", e);
            createCRDs();
        }
    }

    private static void startOperator() {
        app = CDI.current().select(App.class).get();
        app.start(configOverride -> {
            configOverride.withKubernetesClient(client);
            configOverride.withUseSSAToPatchPrimaryResource(false);
        });
    }

    static void applyStrimziResources() throws IOException {
        // TODO: IMPORTANT: Strimzi >0.45 only supports Kraft-based Kafka clusters. Migration needed.
        // var strimziClusterOperatorURL = new URL("https://strimzi.io/install/latest");
        var strimziClusterOperatorURL = new URL("https://github.com/strimzi/strimzi-kafka-operator/releases/download/0.45.0/strimzi-cluster-operator-0.45.0.yaml");
        try (BufferedInputStream in = new BufferedInputStream(strimziClusterOperatorURL.openStream())) {
            List<HasMetadata> resources = Serialization.unmarshal(in);
            resources.forEach(r -> {
                if (r.getKind().equals("ClusterRoleBinding") && r instanceof ClusterRoleBinding) {
                    var crb = (ClusterRoleBinding) r;
                    crb.getSubjects().forEach(s -> s.setNamespace(namespace));
                } else if (r.getKind().equals("RoleBinding") && r instanceof RoleBinding) {
                    var crb = (RoleBinding) r;
                    crb.getSubjects().forEach(s -> s.setNamespace(namespace));
                }
                log.info("Creating Strimzi resource kind {} in namespace {}", r.getKind(), namespace);
                client.resource(r).inNamespace(namespace).createOrReplace();
                await().atMost(Duration.ofMinutes(2)).ignoreExceptions().until(() -> {
                    assertThat(client.resource(r).inNamespace(namespace).get()).isNotNull();
                    return true;
                });
            });
        }
    }

    static void createNamespace(KubernetesClient client, String namespace) {
        log.info("Creating Namespace {}", namespace);
        client.resource(
                        new NamespaceBuilder().withNewMetadata().addToLabels("app", "apicurio-registry-operator-test")
                                .withName(namespace).endMetadata().build())
                .create();
    }

    static String calculateNamespace() {
        return "test-" + UUID.randomUUID().toString().substring(0, 7);
    }

    static void setDefaultAwaitilityTimings() {
        Awaitility.setDefaultPollInterval(POLL_INTERVAL_DURATION);
        Awaitility.setDefaultTimeout(LONG_DURATION);
    }

    static void createResources(List<HasMetadata> resources, String resourceType) {
        resources.forEach(r -> {
            log.info("Creating {} resource kind {} in namespace {}", resourceType, r.getKind(), namespace);
            client.resource(r).inNamespace(namespace).createOrReplace();
            await().ignoreExceptions().until(() -> {
                assertThat(client.resource(r).inNamespace(namespace).get()).isNotNull();
                return true;
            });
        });
    }

    @AfterEach
    public void cleanup() {
        if (cleanup) {
            log.info("Deleting CRs");
            client.resources(ApicurioRegistry3.class).delete();
            await().untilAsserted(() -> {
                var registryDeployments = client.apps().deployments().inNamespace(namespace)
                        .withLabels(Constants.BASIC_LABELS).list().getItems();
                assertThat(registryDeployments.size()).isZero();
            });
        }
    }

    @AfterAll
    public static void after() throws Exception {
        portForwardManager.stop();
        if (operatorDeployment == OperatorDeployment.local) {
            app.stop();
            log.info("Creating new K8s Client");
            // create a new client bc operator has closed the old one
            client = createK8sClient(namespace);
        } else {
            cleanTestResources();
        }
        podLogManager.stopAndWait();
        if (cleanup) {
            log.info("Deleting namespace : {}", namespace);
            assertThat(client.namespaces().withName(namespace).delete()).isNotNull();
        }
        client.close();
    }
}
