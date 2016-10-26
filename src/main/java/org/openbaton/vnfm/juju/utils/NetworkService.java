/*
 * Copyright (c) 2016 Open Baton (http://www.openbaton.org)
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

package org.openbaton.vnfm.juju.utils;

import org.openbaton.catalogue.mano.common.ConnectionPoint;
import org.openbaton.catalogue.mano.descriptor.VirtualDeploymentUnit;
import org.openbaton.catalogue.mano.descriptor.VirtualNetworkFunctionDescriptor;
import org.openbaton.catalogue.mano.record.VNFCInstance;
import org.openbaton.catalogue.mano.record.VirtualNetworkFunctionRecord;
import org.openbaton.catalogue.nfvo.VNFPackage;

import java.util.*;

/**
 * Used to store information about a network service. The information comes from the NFVO.
 *
 * Created by tbr on 25.08.16.
 */
public class NetworkService {

  private String id;
  private List<VirtualNetworkFunctionDescriptor> vnfdList;
  private List<VirtualNetworkFunctionRecord> vnfrList;
  private Map<String, VNFPackage> vnfPackageMap;
  // <target-vnf-name, <source-vnf-name, <parameters>>
  private Map<String, Map<String, List<String>>> dependencies;
  private boolean environmentDeployed;

  private Map<String, String> vnfStatusMap;

  // list of charm names that will be deployed using the juju charm-store
  private List<String> charms;

  public NetworkService() {
    this.vnfdList = new LinkedList<>();
    this.vnfrList = new LinkedList<>();
    this.vnfPackageMap = new HashMap<>();
    this.dependencies = new HashMap<>();
    this.vnfStatusMap = new HashMap<>();
    this.charms = new LinkedList<>();
  }

  public VirtualNetworkFunctionDescriptor getVnfdByName(String name) {
    for (VirtualNetworkFunctionDescriptor vnfd : vnfdList) {
      if (vnfd.getName().equals(name)) return vnfd;
    }
    return null;
  }

  public VirtualNetworkFunctionRecord getVnfrByName(String name) {
    for (VirtualNetworkFunctionRecord vnfr : vnfrList) {
      if (vnfr.getName().equals(name)) return vnfr;
    }
    return null;
  }

  public VNFPackage getVnfPackageByName(String name) {
    return vnfPackageMap.get(name);
  }

  public List<String> getSourcesNames(String targetVnfName) {
    Map<String, List<String>> map = dependencies.get(targetVnfName);
    if (map == null) return new LinkedList<>();
    List<String> returnList = new LinkedList<>();
    returnList.addAll(map.keySet());
    return returnList;
  }

  public List<String> getParameters(String source, String target) {
    Map<String, List<String>> map = dependencies.get(target);
    if (map == null) return new LinkedList<>();
    List<String> paramList = map.get(source);
    if (paramList == null) return new LinkedList<>();
    return paramList;
  }

  public void addVnfd(VirtualNetworkFunctionDescriptor vnfd) {
    for (VirtualNetworkFunctionDescriptor vnfdAlreadyPresent : vnfdList) {
      if (vnfdAlreadyPresent.getId().equals(vnfd.getId())) {
        return;
      }
    }
    vnfdList.add(vnfd);
  }

  public void addVnfr(VirtualNetworkFunctionRecord vnfr) {
    for (VirtualNetworkFunctionRecord vnfrAlreadyPresent : vnfrList) {
      if (vnfrAlreadyPresent.getId().equals(vnfr.getId())) {
        return;
      }
    }
    vnfrList.add(vnfr);
  }

  public void setVnfStatus(String vnf, String status) {
    vnfStatusMap.put(vnf, status);
  }

  /**
   * Returns true if all the VNFs in this NetworkService are in state 'started'.
   *
   * @return
   */
  public boolean isReadyToDeploy() {
    for (Map.Entry status : vnfStatusMap.entrySet()) {
      if (!status.getValue().equals("started")) return false;
    }
    return true;
  }

  /**
   * Returns true if the passed VNF is involved in a dependency.
   *
   * @param vnfName
   * @return
   */
  public boolean vnfHasDependencies(String vnfName) {
    if (vnfIsSource(vnfName) || vnfIsTarget(vnfName)) return true;

    return false;
  }

  /**
   * Returns true if the passed VNF is source of a dependency.
   *
   * @param vnfName
   * @return
   */
  public boolean vnfIsSource(String vnfName) {
    for (Map.Entry<String, Map<String, List<String>>> entry : dependencies.entrySet()) {
      if (entry.getValue().containsKey(vnfName)) return true;
    }

    return false;
  }

  /**
   * Returns true if the passed VNF is target of a dependency.
   *
   * @param vnfName
   * @return
   */
  public boolean vnfIsTarget(String vnfName) {
    if (dependencies.containsKey(vnfName)) return true;

    return false;
  }

  /**
   * Returns a map containing the source VNFs and the corresponding parameters of the passed target
   * VNF.
   *
   * @param vnfName
   * @return
   */
  public Map<String, List<String>> getSourcesOfVnf(String vnfName) {
    return dependencies.get(vnfName);
  }

  /**
   * Returns a map containing the target VNFs and the corresponding parameters of the passed source
   * VNF.
   *
   * @param name
   */
  public Map<String, List<String>> getTargetsOfVnf(String name) {
    Map<String, List<String>> targets = new HashMap<>();
    for (Map.Entry<String, Map<String, List<String>>> targetSourceParams :
        dependencies.entrySet()) {
      for (Map.Entry<String, List<String>> sourceParams :
          targetSourceParams.getValue().entrySet()) {
        if (sourceParams.getKey().equals(name)) {
          targets.put(targetSourceParams.getKey(), sourceParams.getValue());
        }
      }
    }
    return targets;
  }

  /**
   * Get the type of a VNF by passing his name.
   *
   * @param vnfName
   * @return
   */
  public String getVnfTypeForName(String vnfName) {
    for (VirtualNetworkFunctionRecord vnfr : vnfrList)
      if (vnfr.getName().equals(vnfName)) return vnfr.getType();
    return "";
  }

  /**
   * Returns all the parameters that the passed VNF has to provide for fulfilling the dependencies.
   *
   * @param vnfName
   * @return
   */
  public Set<String> getProvidesOfVnf(String vnfName) {
    Set<String> params = new HashSet<>();
    for (Map.Entry<String, Map<String, List<String>>> entry : dependencies.entrySet()) {
      if (entry.getValue().containsKey(vnfName)) {
        String target = entry.getKey();
        params.addAll(entry.getValue().get(vnfName));
      }
    }
    return params;
  }

  public void addVnfPackage(VNFPackage vnfPackage, String vnfName) {
    if (vnfPackageMap.containsKey(vnfName)) return;
    vnfPackageMap.put(vnfName, vnfPackage);
  }

  public VNFPackage getVnfPackage(String vnfName) {
    return vnfPackageMap.get(vnfName);
  }

  /**
   * Add the name of a charm to the charms list.
   *
   * @param charm
   */
  public void addCharm(String charm) {
    charms.add(charm);
  }

  public List<VirtualNetworkFunctionDescriptor> getVnfdList() {
    return vnfdList;
  }

  public void setVnfdList(List<VirtualNetworkFunctionDescriptor> vnfdList) {
    this.vnfdList = vnfdList;
  }

  public Map<String, VNFPackage> getVnfPackageMap() {
    return vnfPackageMap;
  }

  public void setVnfPackageMap(Map<String, VNFPackage> vnfPackageMap) {
    this.vnfPackageMap = vnfPackageMap;
  }

  public Map<String, Map<String, List<String>>> getDependencies() {
    return dependencies;
  }

  public void setDependencies(Map<String, Map<String, List<String>>> dependencies) {
    this.dependencies = dependencies;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public boolean isEnvironmentDeployed() {
    return environmentDeployed;
  }

  public void setEnvironmentDeployed(boolean environmentDeployed) {
    this.environmentDeployed = environmentDeployed;
  }

  public Map<String, String> getVnfStatusMap() {
    return vnfStatusMap;
  }

  public void setVnfStatusMap(Map<String, String> vnfStatusMap) {
    this.vnfStatusMap = vnfStatusMap;
  }

  public void addDependency(String target, String sourceName, List<String> parameters) {
    Map<String, List<String>> sourceParams = new HashMap<>();
    sourceParams.put(sourceName, parameters);
    if (dependencies.containsKey(target)) {
      dependencies.get(target).put(sourceName, parameters);
    } else dependencies.put(target, sourceParams);
  }

  @Override
  public String toString() {
    return "Id: "
        + id
        + "\nDependencies: "
        + dependencies
        + "\nvnfStatusMap: "
        + vnfStatusMap
        + "\nVnfdList: "
        + vnfdList;
  }

  public List<VirtualNetworkFunctionRecord> getVnfrList() {
    return vnfrList;
  }

  public void setVnfrList(List<VirtualNetworkFunctionRecord> vnfrList) {
    this.vnfrList = vnfrList;
  }

  public List<String> getCharms() {
    return charms;
  }

  public void setCharms(List<String> charms) {
    this.charms = charms;
  }
}
