package brooklyn.networking.vclouddirector;

import static com.google.common.base.Preconditions.checkNotNull;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

import javax.xml.bind.JAXBElement;

import org.jclouds.vcloud.director.v1_5.domain.network.Network;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.guava.Maybe;
import brooklyn.util.net.Protocol;

import com.google.api.client.repackaged.com.google.common.base.Objects;
import com.google.common.annotations.Beta;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.net.HostAndPort;
import com.vmware.vcloud.api.rest.schema.GatewayFeaturesType;
import com.vmware.vcloud.api.rest.schema.GatewayNatRuleType;
import com.vmware.vcloud.api.rest.schema.NatRuleType;
import com.vmware.vcloud.api.rest.schema.NatServiceType;
import com.vmware.vcloud.api.rest.schema.NetworkServiceType;
import com.vmware.vcloud.api.rest.schema.ReferenceType;
import com.vmware.vcloud.sdk.ReferenceResult;
import com.vmware.vcloud.sdk.Task;
import com.vmware.vcloud.sdk.VCloudException;
import com.vmware.vcloud.sdk.VcloudClient;
import com.vmware.vcloud.sdk.admin.EdgeGateway;
import com.vmware.vcloud.sdk.admin.extensions.ExtensionQueryService;
import com.vmware.vcloud.sdk.admin.extensions.VcloudAdminExtension;
import com.vmware.vcloud.sdk.constants.Version;
import com.vmware.vcloud.sdk.constants.query.QueryReferenceType;

@Beta
public class NatService {

	private static final Logger LOG = LoggerFactory.getLogger(NatService.class);
	
    private static final List<Version> VCLOUD_VERSIONS = ImmutableList.of(Version.V5_5, Version.V5_1, Version.V1_5);

	public static Builder builder() {
		return new Builder();
	}

    public static class Builder {
        private String identity;
        private String credential;
        private String endpoint;
        private String trustStore;
        private String trustStorePassword;
        private Level logLevel;
        
        public Builder location(JcloudsLocation val) {
        	identity(val.getIdentity());
        	credential(val.getCredential());
        	endpoint(transformEndpoint(val.getEndpoint()));
        	return this;
        }
        private String transformEndpoint(String val) {
            // jclouds endpoint has suffix "/api"; but VMware SDK wants it without "api"
            URI uri = URI.create(val);
            try {
                return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), null, null, null).toString();
            } catch (URISyntaxException e) {
                throw Exceptions.propagate(e);
            } 
        }
        public Builder identity(String val) {
        	this.identity = val; return this;
        }
        public Builder credential(String val) {
        	this.credential = val; return this;
        }
        public Builder endpoint(String val) {
        	this.endpoint = val; return this;
        }
        public Builder trustStore(String val) {
        	this.trustStore = val; return this;
        }
        public Builder trustStorePassword(String val) {
        	this.trustStorePassword = val; return this;
        }
        public Builder logLevel(java.util.logging.Level val) {
            this.logLevel = val; return this;
        }
    	public NatService build() {
    		return new NatService(this);
    	}
    }
    
	private final VcloudClient client;
	private final String baseUrl; // e.g. "https://p5v1-vcd.vchs.vmware.com:443";

    public NatService(Builder builder) {
    	client = newVcloudClient(checkNotNull(builder.endpoint, "endpoint"), checkNotNull(builder.identity, "identity"), 
    			checkNotNull(builder.credential, "credential"), builder.trustStore, builder.trustStorePassword, builder.logLevel);
    	baseUrl = builder.endpoint;
    }

    public static class OpenPortForwardingConfig {
    	private Protocol protocol;
    	private HostAndPort target;
    	private Network network;
    	private String publicIp;
    	private Integer publicPort;
    	
    	public OpenPortForwardingConfig protocol(Protocol val) {
    		this.protocol = val; return this;
    	}
    	public OpenPortForwardingConfig network(Network val) {
    		this.network = val; return this;
    	}
    	public OpenPortForwardingConfig target(HostAndPort val) {
    		this.target = val; return this;
    	}
    	public OpenPortForwardingConfig publicIp(String val) {
    		this.publicIp = val; return this;
    	}
    	public OpenPortForwardingConfig publicPort(int val) {
    		this.publicPort = val; return this;
    	}
        public void checkValid() {
        	checkNotNull(protocol, "protocol");
        	checkNotNull(target, "target");
        	checkNotNull(network, "network");
        	checkNotNull(publicIp, "publicIp");
            checkNotNull(publicPort, publicPort);
        }
    	@Override
    	public String toString() {
    		return Objects.toStringHelper(this).add("protocol", protocol).add("target", target).add("network", network)
    				.add("publicIp", publicIp).add("publicPort", publicPort).toString();
    	}
    }
    public HostAndPort openPortForwarding(OpenPortForwardingConfig args) throws VCloudException {
        // Append DNAT rule to NAT service; retrieve the existing, modify it, and upload.
        // If instead we create new objects then risk those having different config - this is *not* a delta!
        
    	args.checkValid();
    	if (LOG.isDebugEnabled()) LOG.debug("Opening port forwarding at {}: {}", baseUrl, args);
    	
        EdgeGateway edgeGateway = getEdgeGateway();
        GatewayFeaturesType gatewayFeatures = getGatewayFeatures(edgeGateway);
        NatServiceType natService = tryFindService(gatewayFeatures.getNetworkService(), NatServiceType.class).get();
        
        // Modify the natService (which is the object retrieved directly from edgeGateway)
        ReferenceType interfaceRef = generateInterfaceRef(args.network);

        GatewayNatRuleType gatewayNatRule = generateGatewayNatRule(
                args.protocol, 
                HostAndPort.fromParts(args.publicIp, args.publicPort), 
                args.target, 
                interfaceRef);
        NatRuleType dnatRule = generateDnatRule(true, gatewayNatRule);

        natService.getNatRule().add(dnatRule);
        
        // Execute task
        Task task = edgeGateway.configureServices(gatewayFeatures);
        waitForTask(task, "add dnat rule");

        // Confirm that the newly created rule exists, 
        // with the expected translated (i.e internal) and original (i.e. public) addresses,
        // and without any conflicting DNAT rules already using that port.
        // Retrieves a new EdgeGateway instance, to ensure we're not just looking at our local copy.
        List<NatRuleType> rules = getNatRules(getEdgeGateway());
        
        Iterable<NatRuleType> matches = Iterables.filter(rules, Predicates.and(
                NatPredicates.originalTargetEquals(args.publicIp, args.publicPort),
                NatPredicates.translatedTargetEquals(args.target.getHostText(), args.target.getPort())));
        
        Iterable<NatRuleType> conflicts = Iterables.filter(rules, Predicates.and(
                NatPredicates.originalTargetEquals(args.publicIp, args.publicPort),
                Predicates.not(NatPredicates.translatedTargetEquals(args.target.getHostText(), args.target.getPort()))));
        
        if (Iterables.isEmpty(matches)) {
            throw new IllegalStateException(
                    String.format("Gateway NAT Rules: cannot find translated %s and original %s:%s at %s", 
                            args.target, args.publicIp, args.publicPort, baseUrl));
        } else if (Iterables.size(matches) > 1) {
            LOG.warn(String.format("Gateway NAT Rules: %s duplicates for translated %s and original %s:%s at %s; continuing.", 
                    Iterables.size(matches), args.target, args.publicIp, args.publicPort, baseUrl));
        }
        if (Iterables.size(conflicts) > 0) {
            throw new IllegalStateException(
                    String.format("Gateway NAT Rules: original already assigned for translated %s and original %s:%s at %s", 
                            args.target, args.publicIp, args.publicPort, baseUrl));
        }

        return HostAndPort.fromParts(args.publicIp, args.publicPort);
    }

    public void enableNatService() throws VCloudException {
        if (LOG.isDebugEnabled()) LOG.debug("Enabling NAT Service at {}", baseUrl);
        
        EdgeGateway edgeGateway = getEdgeGateway();
        GatewayFeaturesType gatewayFeatures = getGatewayFeatures(edgeGateway);
        NatServiceType natService = tryFindService(gatewayFeatures.getNetworkService(), NatServiceType.class).get();

        // Modify
        natService.setIsEnabled(true);
        
        // Execute task
        Task task = edgeGateway.configureServices(gatewayFeatures);
        waitForTask(task, "enable nat-service");
    }

    protected void waitForTask(Task task, String summary) throws VCloudException {
        checkNotNull(task, "task null for %s", summary);
        try {
            task.waitForTask(0);
        } catch (TimeoutException e) {
            throw Exceptions.propagate(e);
        }
    }
    
    protected EdgeGateway getEdgeGateway() throws VCloudException {
        List<ReferenceType> edgeGatewayRef = queryEdgeGateways();
        return EdgeGateway.getEdgeGatewayById(client, edgeGatewayRef.get(0).getId());
    }

    protected GatewayFeaturesType getGatewayFeatures(EdgeGateway edgeGateway) {
        return edgeGateway
                .getResource()
                .getConfiguration()
                .getEdgeGatewayServiceConfiguration();
    }

    protected List<JAXBElement<? extends NetworkServiceType>> getNetworkServices(EdgeGateway edgeGateway) {
        return getGatewayFeatures(edgeGateway).getNetworkService();
    }

    protected <T extends NetworkServiceType> Maybe<T> tryFindService(List<JAXBElement<? extends NetworkServiceType>> services, Class<T> type) {
        for (JAXBElement<? extends NetworkServiceType> service : services) {
            if (service.getDeclaredType().getSimpleName().equals(type.getSimpleName())) {
                return Maybe.of(type.cast(service.getValue()));
            }
        }
        return Maybe.absent("No service of type "+type.getSimpleName());
    }
    
    protected List<NatRuleType> getNatRules(EdgeGateway edgeGateway) {
        List<JAXBElement<? extends NetworkServiceType>> services = getNetworkServices(edgeGateway);
        Maybe<NatServiceType> natService = tryFindService(services, NatServiceType.class);
        return (natService.isPresent()) ? natService.get().getNatRule() : new ArrayList<NatRuleType>();
    }

    private List<ReferenceType> queryEdgeGateways() throws VCloudException {
        // Getting the VcloudAdminExtension
        VcloudAdminExtension adminExtension = client.getVcloudAdminExtension();

        // Getting the Admin Extension Query Service.
        ExtensionQueryService queryService = adminExtension.getExtensionQueryService();
        ReferenceResult referenceResult = queryService.queryReferences(QueryReferenceType.EDGEGATEWAY);
        return referenceResult.getReferences();
    }

    private static ReferenceType generateInterfaceRef(Network network) {
        ReferenceType interfaceRef = new ReferenceType();
        interfaceRef.setHref(network.getHref().toString());
        interfaceRef.setName(network.getName());
        interfaceRef.setType(network.getType());
        return interfaceRef;
    }

    // FIXME Don't set sysprop as could affect all other activities of the JVM!
    protected VcloudClient newVcloudClient(String endpoint, String identity, String credential, String trustStore, String trustStorePassword, Level logLevel) {
    	try {
    	    if (logLevel != null) {
    	        // Logging is extremely verbose at INFO - it logs in full every http request/response (including payload).
    	        // Consider setting this to WARN; leaving as default is not explicitly set
    	        VcloudClient.setLogLevel(logLevel);
    	    }
    	    
    		// Client login
            VcloudClient vcloudClient = null;
            boolean versionFound = false;
            for (Version version : VCLOUD_VERSIONS) {
                try {
                    vcloudClient = new VcloudClient(endpoint, version);
                    LOG.debug("VCloudClient - trying login to {} using {}", endpoint, version);
                    vcloudClient.login(identity, credential);
                    versionFound = true;
                    LOG.info("VCloudClient - Logged into {} using version {}", endpoint, version);
                    break;
                } catch (VCloudException e) {
                    LOG.debug("VCloudClient - Cannot login to " + endpoint + " using " + version, e);
                }
            }
            if (!versionFound) {
                throw new IllegalStateException("Cannot login to " + endpoint + " using any of " + VCLOUD_VERSIONS);
            }
            
            // Performing Certificate Validation
			if (trustStore != null) {
				System.setProperty("javax.net.ssl.trustStore", trustStore);
				System.setProperty("javax.net.ssl.trustStorePassword", trustStorePassword);
				vcloudClient.registerScheme("https", 443, CustomSSLSocketFactory.getInstance());

			} else {
                LOG.warn("Ignoring the Certificate Validation using FakeSSLSocketFactory");
                vcloudClient.registerScheme("https", 443, FakeSSLSocketFactory.getInstance());
			}
			return vcloudClient;
    	} catch (Exception e) {
    		throw Exceptions.propagate(e);
    	}
    }

    private GatewayNatRuleType generateGatewayNatRule(Protocol protocol, HostAndPort original,
            HostAndPort translated, ReferenceType interfaceRef) {
        GatewayNatRuleType gatewayNatRule = new GatewayNatRuleType();
        gatewayNatRule.setProtocol(protocol.toString());
        gatewayNatRule.setOriginalIp(original.getHostText());
        gatewayNatRule.setOriginalPort(""+original.getPort());
        gatewayNatRule.setTranslatedIp(translated.getHostText());
        gatewayNatRule.setTranslatedPort(""+translated.getPort());
        gatewayNatRule.setInterface(interfaceRef);
        return gatewayNatRule;
    }

    private NatRuleType generateDnatRule(boolean enabled, GatewayNatRuleType gatewayNatRule) {
        NatRuleType dnatRule = new NatRuleType();
        dnatRule.setIsEnabled(enabled);
        dnatRule.setRuleType("DNAT");
        dnatRule.setGatewayNatRule(gatewayNatRule);
        return dnatRule;
    }
}
