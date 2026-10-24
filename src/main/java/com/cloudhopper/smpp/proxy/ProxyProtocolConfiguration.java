package com.cloudhopper.smpp.proxy;

/*
 * #%L
 * ch-smpp
 * %%
 * Copyright (C) 2009 - 2015 Cloudhopper by Twitter
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

// java imports
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration of opt-in PROXY protocol support on an SMPP server. When
 * enabled, only connections from the trusted proxy networks may send a PROXY
 * protocol header that replaces the peer address of the connection.
 */
public class ProxyProtocolConfiguration {

    private boolean enabled;
    // CIDR networks (or bare addresses) of the proxies allowed to send a header
    private List<String> trustedProxies;

    public ProxyProtocolConfiguration() {
        this.enabled = false;
        this.trustedProxies = new ArrayList<>();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getTrustedProxies() {
        return trustedProxies;
    }

    /**
     * Sets the networks allowed to send a PROXY protocol header, in CIDR
     * notation (e.g. "10.0.0.0/8", "2001:db8::/32") or as bare addresses.
     * @param trustedProxies The trusted networks
     */
    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    /**
     * Checks the configuration. When enabled, the trusted proxies must be
     * present and every entry must be a valid CIDR network. A disabled
     * configuration is always valid.
     * @throws IllegalArgumentException if the configuration is invalid
     */
    public void validate() {
        if (!enabled) {
            return;
        }
        if (trustedProxies == null || trustedProxies.isEmpty()) {
            throw new IllegalArgumentException("PROXY protocol is enabled but no trusted proxies are configured");
        }
        TrustedNetworks.parse(trustedProxies);
    }
}
