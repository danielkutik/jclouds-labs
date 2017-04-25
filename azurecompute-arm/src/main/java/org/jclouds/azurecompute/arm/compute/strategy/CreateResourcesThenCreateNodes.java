/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jclouds.azurecompute.arm.compute.strategy;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Iterables.getOnlyElement;
import static org.jclouds.azurecompute.arm.config.AzureComputeProperties.DEFAULT_SUBNET_ADDRESS_PREFIX;
import static org.jclouds.azurecompute.arm.config.AzureComputeProperties.DEFAULT_VNET_ADDRESS_SPACE_PREFIX;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import javax.annotation.Resource;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.jclouds.Constants;
import org.jclouds.azurecompute.arm.AzureComputeApi;
import org.jclouds.azurecompute.arm.compute.domain.ResourceGroupAndName;
import org.jclouds.azurecompute.arm.compute.domain.ResourceGroupAndNameAndIngressRules;
import org.jclouds.azurecompute.arm.compute.functions.TemplateToAvailabilitySet;
import org.jclouds.azurecompute.arm.compute.options.AzureTemplateOptions;
import org.jclouds.azurecompute.arm.domain.AvailabilitySet;
import org.jclouds.azurecompute.arm.domain.NetworkSecurityGroup;
import org.jclouds.azurecompute.arm.domain.ResourceGroup;
import org.jclouds.azurecompute.arm.domain.Subnet;
import org.jclouds.azurecompute.arm.domain.VirtualNetwork;
import org.jclouds.azurecompute.arm.features.SubnetApi;
import org.jclouds.azurecompute.arm.features.VirtualNetworkApi;
import org.jclouds.compute.config.CustomizationResponse;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.functions.GroupNamingConvention;
import org.jclouds.compute.reference.ComputeServiceConstants;
import org.jclouds.compute.strategy.CreateNodeWithGroupEncodedIntoName;
import org.jclouds.compute.strategy.CustomizeNodeAndAddToGoodMapOrPutExceptionIntoBadMap;
import org.jclouds.compute.strategy.ListNodesStrategy;
import org.jclouds.compute.strategy.impl.CreateNodesWithGroupEncodedIntoNameThenAddToSet;
import org.jclouds.domain.Location;
import org.jclouds.logging.Logger;

import com.google.common.base.Optional;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

@Singleton
public class CreateResourcesThenCreateNodes extends CreateNodesWithGroupEncodedIntoNameThenAddToSet {

   @Resource
   @Named(ComputeServiceConstants.COMPUTE_LOGGER)
   protected Logger logger = Logger.NULL;

   private final AzureComputeApi api;
   private final LoadingCache<ResourceGroupAndNameAndIngressRules, String> securityGroupMap;
   private final String defaultVnetAddressPrefix;
   private final String defaultSubnetAddressPrefix;
   private final TemplateToAvailabilitySet templateToAvailabilitySet;

   @Inject
   protected CreateResourcesThenCreateNodes(
         CreateNodeWithGroupEncodedIntoName addNodeWithGroupStrategy,
         ListNodesStrategy listNodesStrategy,
         GroupNamingConvention.Factory namingConvention,
         @Named(Constants.PROPERTY_USER_THREADS) ListeningExecutorService userExecutor,
         CustomizeNodeAndAddToGoodMapOrPutExceptionIntoBadMap.Factory customizeNodeAndAddToGoodMapOrPutExceptionIntoBadMapFactory,
         AzureComputeApi api, @Named(DEFAULT_VNET_ADDRESS_SPACE_PREFIX) String defaultVnetAddressPrefix,
         @Named(DEFAULT_SUBNET_ADDRESS_PREFIX) String defaultSubnetAddressPrefix,
         LoadingCache<ResourceGroupAndNameAndIngressRules, String> securityGroupMap,
         TemplateToAvailabilitySet templateToAvailabilitySet) {
      super(addNodeWithGroupStrategy, listNodesStrategy, namingConvention, userExecutor,
            customizeNodeAndAddToGoodMapOrPutExceptionIntoBadMapFactory);
      this.api = checkNotNull(api, "api cannot be null");
      checkNotNull(userExecutor, "userExecutor cannot be null");
      this.securityGroupMap = securityGroupMap;
      this.defaultVnetAddressPrefix = defaultVnetAddressPrefix;
      this.defaultSubnetAddressPrefix = defaultSubnetAddressPrefix;
      this.templateToAvailabilitySet = templateToAvailabilitySet;
   }

   @Override
   public Map<?, ListenableFuture<Void>> execute(String group, int count, Template template,
         Set<NodeMetadata> goodNodes, Map<NodeMetadata, Exception> badNodes,
         Multimap<NodeMetadata, CustomizationResponse> customizationResponses) {

      AzureTemplateOptions options = template.getOptions().as(AzureTemplateOptions.class);

      // If there is a script to be run on the node and public key
      // authentication has been configured, warn users if the private key
      // is not present
      if (hasRunScriptWithKeyAuthAndNoPrivateKey(template)) {
         logger.warn(">> a runScript was configured but no SSH key has been provided. "
               + "Authentication will delegate to the ssh-agent");
      }

      // This sill create the resource group if it does not exist
      String location = template.getLocation().getId();

      createResourceGroupIfNeeded(group, location, options);
      getOrCreateVirtualNetworkWithSubnet(location, options);
      configureSecurityGroupForOptions(group, template.getLocation(), options);
      configureAvailabilitySetForTemplate(template);

      return super.execute(group, count, template, goodNodes, badNodes, customizationResponses);
   }

   protected synchronized void getOrCreateVirtualNetworkWithSubnet(final String location, AzureTemplateOptions options) {
      String virtualNetworkName = Optional.fromNullable(options.getVirtualNetworkName()).or(
            options.getResourceGroup() + "virtualnetwork");
      String subnetName = options.getResourceGroup() + "subnet";

      // Subnets belong to a virtual network so that needs to be created first
      VirtualNetworkApi vnApi = api.getVirtualNetworkApi(options.getResourceGroup());
      VirtualNetwork vn = vnApi.get(virtualNetworkName);

      if (vn == null) {
         Subnet subnet = Subnet.create(subnetName, null, null,
               Subnet.SubnetProperties.builder().addressPrefix(defaultSubnetAddressPrefix).build());

         VirtualNetwork.VirtualNetworkProperties virtualNetworkProperties = VirtualNetwork.VirtualNetworkProperties
               .builder().addressSpace(VirtualNetwork.AddressSpace.create(Arrays.asList(defaultVnetAddressPrefix)))
               .subnets(Arrays.asList(subnet)).build();

         vn = vnApi.createOrUpdate(virtualNetworkName, location, virtualNetworkProperties);
      }

      SubnetApi subnetApi = api.getSubnetApi(options.getResourceGroup(), virtualNetworkName);
      Subnet subnet = subnetApi.get(subnetName);

      options.virtualNetworkName(virtualNetworkName);
      options.subnetId(subnet.id());
   }

   private static boolean hasRunScriptWithKeyAuthAndNoPrivateKey(Template template) {
      return template.getOptions().getRunScript() != null && template.getOptions().getPublicKey() != null
            && !template.getOptions().hasLoginPrivateKeyOption();
   }

   private void configureSecurityGroupForOptions(String group, Location location, AzureTemplateOptions options) {

      checkArgument(options.getGroups().size() <= 1,
            "Only one security group can be configured for each network interface");

      if (!options.getGroups().isEmpty()) {
         ResourceGroupAndName securityGroupId = ResourceGroupAndName.fromSlashEncoded(getOnlyElement(options.getGroups()));
         NetworkSecurityGroup securityGroup = api.getNetworkSecurityGroupApi(securityGroupId.resourceGroup()).get(
               securityGroupId.name());
         checkArgument(securityGroup != null, "Security group %s was not found", securityGroupId.slashEncode());
         options.securityGroups(securityGroup.id());
      } else if (options.getInboundPorts().length > 0) {
         String name = namingConvention.create().sharedNameForGroup(group);
         ResourceGroupAndNameAndIngressRules regionAndIdAndIngressRules = ResourceGroupAndNameAndIngressRules.create(
               options.getResourceGroup(), location.getId(), name, options.getInboundPorts());
         // this will create if not yet exists.
         String securityGroupId = securityGroupMap.getUnchecked(regionAndIdAndIngressRules);
         options.securityGroups(securityGroupId);
      }
   }
   
   private void configureAvailabilitySetForTemplate(Template template) {
      AvailabilitySet availabilitySet = templateToAvailabilitySet.apply(template);
      if (availabilitySet != null) {
         logger.debug(">> configuring nodes in availability set [%s]", availabilitySet.name());
         template.getOptions().as(AzureTemplateOptions.class).availabilitySet(availabilitySet);
      }
   }
   
   private void createResourceGroupIfNeeded(String group, String location, AzureTemplateOptions options) {
      if (options.getResourceGroup() == null) {
         options.resourceGroup(group);
      }
      logger.debug(">> using resource group [%s]", options.getResourceGroup());
      ResourceGroup rg = api.getResourceGroupApi().get(options.getResourceGroup());
      if (rg == null) {
         logger.debug(">> resource group [%s] does not exist. Creating!", options.getResourceGroup());
         api.getResourceGroupApi().create(options.getResourceGroup(), location,
               ImmutableMap.of("description", "jclouds default resource group"));
      }
   }
}