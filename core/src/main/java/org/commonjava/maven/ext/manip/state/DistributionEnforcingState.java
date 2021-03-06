/**
 * Copyright (C) 2012 Red Hat, Inc. (jcasey@redhat.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.maven.ext.manip.state;

import java.util.Properties;

import org.commonjava.maven.ext.manip.impl.DistributionEnforcingManipulator;

/**
 * Captures configuration relating to enforcing distribution (install/deploy) configurations within the POM(s). Used by {@link DistributionEnforcingManipulator}.
 */
public class DistributionEnforcingState
    implements State
{
    /**
     * Property used to set the enforcement mode.
     */
    public static final String ENFORCE_SYSPROP = "enforce-skip";

    /**
     * Property prefix used to exclude certain projects in the current build (specified as g:a) from install/deploy plugin skip-flag enforcement.
     */
    public static final String PROJECT_EXCLUSION_PREFIX = "enforceSkip.";
    
    private final EnforcingMode mode;

    public DistributionEnforcingState( final Properties userProps )
    {
        final String value = userProps.getProperty( ENFORCE_SYSPROP );
        this.mode = EnforcingMode.getMode( value );
    }
    
    public EnforcingMode getEnforcingMode()
    {
        return mode;
    }

    /**
     * Enabled ONLY if enforce-skip is provided in the user properties / CLI -D options, AND that mode is != <code>none</code>.
     *
     * @see #ENFORCE_SYSPROP
     * @see org.commonjava.maven.ext.manip.state.State#isEnabled()
     * @see EnforcingMode
     */
    @Override
    public boolean isEnabled()
    {
        return mode != EnforcingMode.none;
    }

}
