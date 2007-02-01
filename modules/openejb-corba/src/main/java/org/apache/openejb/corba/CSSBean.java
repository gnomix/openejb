/**
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
package org.apache.openejb.corba;

import java.net.URI;

import javax.transaction.TransactionManager;

import edu.emory.mathcs.backport.java.util.concurrent.Executor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.geronimo.gbean.AbstractName;
import org.apache.geronimo.gbean.GBeanLifecycle;
import org.apache.openejb.corba.security.config.ConfigAdapter;
import org.apache.openejb.corba.security.config.css.CSSConfig;
import org.apache.openejb.corba.security.config.ssl.SSLConfig;
import org.apache.openejb.corba.security.config.tss.TSSConfig;
import org.apache.openejb.corba.security.ClientPolicy;
import org.apache.openejb.corba.transaction.ClientTransactionPolicyConfig;
import org.apache.openejb.corba.transaction.ClientTransactionPolicy;
import org.apache.openejb.corba.transaction.nodistributedtransactions.NoDTxClientTransactionPolicyConfig;
import org.omg.CORBA.ORB;
import org.omg.CORBA.UserException;
import org.omg.CORBA.PolicyManager;
import org.omg.CORBA.Policy;
import org.omg.CORBA.SetOverrideType;
import org.omg.CosNaming.NameComponent;
import org.omg.CosNaming.NamingContextExt;
import org.omg.CosNaming.NamingContextExtHelper;


/**
 * A CSSBean is an ORB instance configured for
 * accessing EJBs using a specific security profile.  A single
 * CSSBean can be referenced by multiple ejb-refs that share a
 * common security profile.
 *
 * For each CSSBean instance, there will be a backing
 * ORB configured with the appropriate interceptors and
 * principal information to access the target object.
 * @version $Revision$ $Date$
 */
public class CSSBean implements GBeanLifecycle, ORBConfiguration {

    private final static Log log = LogFactory.getLog(CSSBean.class);

    private final ClassLoader classLoader;
    private final ConfigAdapter configAdapter;
    private final TransactionManager transactionManager;
    private String description;
    private CSSConfig cssConfig;
    private SSLConfig sslConfig;
    // ORB used for activating and accessing the target bean.
    private ORB cssORB;
    // ORB used for nameservice lookups.
    private ORB nssORB;
    private AbstractName abstractName;

    public CSSBean() {
        this.classLoader = null;
        this.configAdapter = null;
        this.transactionManager = null;
        this.abstractName = null;
        this.sslConfig = null;
        this.cssConfig = null;
    }

    public CSSBean(ConfigAdapter configAdapter, TransactionManager transactionManager, SSLConfig ssl, AbstractName abstractName, ClassLoader classLoader) throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        this.abstractName = abstractName;
        this.classLoader = classLoader;
        this.transactionManager = transactionManager;
        this.configAdapter = configAdapter;
        this.sslConfig = ssl;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public CSSConfig getCssConfig() {
        return cssConfig;
    }

    public void setCssConfig(CSSConfig config) {
        if (config == null) config = new CSSConfig();
        this.cssConfig = config;
    }

    public TSSConfig getTssConfig() {
        // just return a default no security one.
        return new TSSConfig();
    }

    /**
     * Return the SSLConfig used for this ORB instance.
     * if one has not been configured, this returns
     * a default configuration.
     *
     * @return The SSLConfig object use to manage transport-level
     *         security.
     */
    public SSLConfig getSslConfig() {
        if (sslConfig == null) {
            sslConfig = new SSLConfig();
        }
        return sslConfig;
    }

    public ORB getORB() {
        return cssORB;
    }

    /**
     * Return the retrieval URI for this bean.
     *
     * @return The URI for the bean AbstractName;
     */
    public String getURI() {
        return abstractName.toString();
    }

    public org.omg.CORBA.Object getHome(URI nsURI, String name) {

        if (log.isDebugEnabled())
            log.debug(description + " - Looking up home from " + nsURI.toString() + " at " + name);

        try {
            // The following may seem unncecessary, but it isn't.  We need to use one ORB to
            // retrieve the object reference from the NameService because the SecurityInterceptor
            // attached to the main ORB instance may add additional service contexts to the
            // NameService request that will cause failures.  We use one configuration to access
            // the server, and the activate the object on the real one.
            org.omg.CORBA.Object ref = nssORB.string_to_object(nsURI.toString());
            NamingContextExt ic = NamingContextExtHelper.narrow(ref);

            NameComponent[] nameComponent = ic.to_name(name);
            org.omg.CORBA.Object bean = ic.resolve(nameComponent);
            return bean;
        } catch (UserException ue) {
            log.error(description + " - Looking up home", ue);
            throw new RuntimeException(ue);
        }
    }

    /**
     * Start this GBean instance, which essentially
     * sets up an ORB and configures a client context
     * for handling requests.
     *
     * @exception Exception
     */
    public void doStart() throws Exception {

        // we create a dummy CSSConfig if one has not be specified prior to this.
        if (cssConfig == null) {
            cssConfig = new CSSConfig();
        }

        ClassLoader savedLoader = Thread.currentThread().getContextClassLoader();
        try {
            log.debug("Starting CSS ORB " + getURI());

            Thread.currentThread().setContextClassLoader(classLoader);

            // create an ORB using the name service.
            nssORB = configAdapter.createNameServiceClientORB(this);
            // the configAdapter creates the ORB instance for us.
            cssORB = configAdapter.createClientORB(this);
            PolicyManager policyManager = (PolicyManager) cssORB.resolve_initial_references("ORBPolicyManager");
            Policy[] policies = new Policy[] {new ClientPolicy(cssConfig), new ClientTransactionPolicy(buildClientTransactionPolicyConfig())};
            policyManager.set_policy_overrides(policies, SetOverrideType.ADD_OVERRIDE);
        } finally {
            Thread.currentThread().setContextClassLoader(savedLoader);
        }

        log.debug("Started CORBA Client Security Server - " + description);
    }

    private ClientTransactionPolicyConfig buildClientTransactionPolicyConfig() {
        return new NoDTxClientTransactionPolicyConfig(transactionManager);
    }

    public void doStop() throws Exception {
        cssORB.destroy();
        nssORB.destroy();
        cssORB = null;
        nssORB = null;
        log.debug("Stopped CORBA Client Security Server - " + description);
    }

    public void doFail() {
        log.debug("Failed CORBA Client Security Server " + description);
    }

}
