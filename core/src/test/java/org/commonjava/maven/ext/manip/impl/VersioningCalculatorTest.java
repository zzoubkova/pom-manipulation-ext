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
package org.commonjava.maven.ext.manip.impl;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Writer;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.repository.DefaultMirrorSelector;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusContainer;
import org.commonjava.maven.atlas.ident.ref.ProjectRef;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.ext.manip.ManipulationException;
import org.commonjava.maven.ext.manip.ManipulationSession;
import org.commonjava.maven.ext.manip.fixture.StubTransport;
import org.commonjava.maven.ext.manip.model.Project;
import org.commonjava.maven.ext.manip.resolver.GalleyAPIWrapper;
import org.commonjava.maven.ext.manip.resolver.GalleyInfrastructure;
import org.commonjava.maven.ext.manip.resolver.MavenLocationExpander;
import org.commonjava.maven.ext.manip.state.VersioningState;
import org.commonjava.maven.galley.model.Location;
import org.commonjava.maven.galley.spi.transport.Transport;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class VersioningCalculatorTest
{

    private static final String GROUP_ID = "group.id";

    private static final String ARTIFACT_ID = "artifact-id";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private TestVersionCalculator modder;

    private ManipulationSession session;

    @Test
    public void initFailsWithoutSuffixProperty()
        throws Exception
    {
        final VersioningState session = setupSession( new Properties() );
        assertThat( session.isEnabled(), equalTo( false ) );
    }

    @Test
    public void applyNonSerialSuffix_NonNumericVersionTail_WithProperty()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "${property}";

        final String result = calculate( v );
        assertThat( result, equalTo( v + "-" + s ) );
    }


    @Test
    public void osgi_fixups()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        String data[][] = new String[][] {
            {"1", "1.0.0"},
            {"6.2.0-SNAPSHOT", "6.2.0"},
            {"1.21", "1.21.0"},
            {"1.21.0", "1.21.0"},
            {"1.21-GA", "1.21.0.GA"},
            {"1.21-GA-GA", "1.21.0.GA-GA"},
            {"1.21.0.GA", "1.21.0.GA"},
            {"1.21.GA", "1.21.0.GA"},
            {"1.21.GA_FINAL", "1.21.0.GA_FINAL"},
            {"1.21.GA_ALPHA123", "1.21.0.GA_ALPHA123"},
            {"1.21.0-GA", "1.21.0.GA"}
            };

        for ( String[] v : data )
        {
            final String result = calculate( v[0] );

            // If expected result contains a qualifier append a '-' instead of '.'
            if ( v[1].contains( "GA" ))
            {
                assertThat( result, equalTo( v[1] + "-" + s ) );
            }
            else
            {
                assertThat( result, equalTo( v[1] + "." + s ) );
            }
        }
    }

    @Test
    /**
     * Not every version form know to man is converted...but we should try to
     * safely handle unknown forms.
     *
     * @throws Exception
     */
    public void osgi_fallback()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        String data[][] = new String[][] {
            {"GA-1-GA", "GA-1-GA"},
            {"1.0.0.0.0-GA", "1.0.0.0-0-GA"}  };

        for ( String[] v : data )
        {
            final String result = calculate( v[0] );

            if ( v[1].contains( "GA" ))
            {
                assertThat( result, equalTo( v[1] + "-" + s ) );
            }
            else
            {
                assertThat( result, equalTo( v[1] + "." + s ) );
            }
        }
    }

    @Test
    public void applyNonSerialSuffix_NonNumericVersionTail_WithOSGiDisabled()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        props.setProperty( VersioningState.VERSION_OSGI_SYSPROP, "false" );
        setupSession( props );

        final String v = "1.2.GA";

        final String result = calculate( v );
        assertThat( result, equalTo( v + "-" + s ) );
    }


    @Test
    public void indempotency()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "1.2.0";

        String result = calculate( v );
        assertThat( result, equalTo( v + "." + s ) );

        result = calculate( result );
        assertThat( result, equalTo( v + "." + s ) );
    }

    @Test
    public void applyNonSerialSuffix_NumericVersionTail()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "1.2.0";

        final String result = calculate( v );
        assertThat( result, equalTo( v + "." + s ) );
    }

    @Test
    public void applyNonSerialSuffix_NumericVersionTail_CompoundQualifier()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo-bar";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "1.2.0";

        final String result = calculate( v );
        assertThat( result, equalTo( v + "." + s ) );
    }

    @Test
    public void applyNonSerialSuffix_NonNumericVersionTail()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "1.2.0.GA";

        final String result = calculate( v );
        assertThat( result, equalTo( v + "-" + s ) );
    }

    @Test
    public void applySerialSuffix_SPnVersionTail()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo-1";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "1.2.0.SP4";

        final String result = calculate( v );
        assertThat( result, equalTo( v + "-" + s ) );
    }

    @Test
    public void applySerialSuffix_NumericVersionTail()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo-1";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "1.2.0";

        final String result = calculate( v );
        assertThat( result, equalTo( v + "." + s ) );
    }

    @Test
    public void applySerialSuffix_NumericVersionTail_Snapshot()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "ER2-rht-1";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "6.2.0-SNAPSHOT";
        final String vr = "6.2.0";

        final String result = calculate( v );
        assertThat( result, equalTo( vr + "." + s ) );
    }

    @Test
    public void applySerialSuffix_NumericVersionTail_CompoundQualifier()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo-bar-1";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "1.2.0";

        final String result = calculate( v );
        assertThat( result, equalTo( v + "." + s ) );
    }

    @Test
    public void applySerialSuffix_NonNumericSuffixInVersionTail()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo-1";
        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "1.2.0.GA-foo";

        final String result = calculate( v );
        assertThat( result, equalTo( v + "-1" ) );
    }

    @Test
    public void applySerialSuffix_SimpleSuffixProperty()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo";
        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String originalVersion = "1.0.0.Final";
        final String calcdVersion = "1.0.0.Final-foo-1";

        final String result = calculate( originalVersion );
        assertThat( result, equalTo( calcdVersion ) );
    }

    @Test
    public void applySerialSuffix_NonNumericNonSuffixInVersionTail()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo-1";
        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "1.2.0.GA-jdcasey";

        final String result = calculate( v );
        assertThat( result, equalTo( v + "-" + s ) );
    }

    @Test
    public void applySerialSuffix_NonNumericVersionTail()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo-1";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "1.2.0.GA";

        final String result = calculate( v );
        assertThat( result, equalTo( v + "-" + s ) );
    }

    @Test
    public void applySerialSuffix_NumericVersionTail_OverwriteExisting()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo-2";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "1.2.0";
        final String os = ".foo-1";

        final String result = calculate( v + os );
        assertThat( result, equalTo( v + "." + s ) );
    }

    @Test
    public void applySerialSuffix_NonNumericVersionTail_OverwriteExisting()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo-2";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String v = "1.2.0.GA";
        final String os = "-foo-1";

        final String result = calculate( v + os );
        assertThat( result, equalTo( v + "-" + s ) );
    }

    @Test
    public void applySuffixBeforeSNAPSHOT()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo-2";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        props.setProperty( VersioningState.VERSION_SUFFIX_SNAPSHOT_SYSPROP, "true" );
        setupSession( props );

        final String v = "1.2.0.GA";
        final String sn = "-SNAPSHOT";

        final String result = calculate( v + sn );
        assertThat( result, equalTo( v + "-" + s + sn ) );
    }

    @Test
    public void applySuffixBeforeSNAPSHOT_OSGI()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo-2";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        props.setProperty( VersioningState.VERSION_SUFFIX_SNAPSHOT_SYSPROP, "true" );
        setupSession( props );

        final String v = "1.2";
        final String sn = "-SNAPSHOT";

        final String result = calculate( v + sn );

        assertThat( result, equalTo( v + ".0." + s + sn ) );
    }

    @Test
    public void applySuffixBeforeSNAPSHOT_OverwriteExisting()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo-2";
        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, s );
        props.setProperty( VersioningState.VERSION_SUFFIX_SNAPSHOT_SYSPROP, "true" );
        setupSession( props );

        final String v = "1.2.0.GA";
        final String sn = "-SNAPSHOT";
        final String os = "-foo-1";

        final String result = calculate( v + os + sn );
        assertThat( result, equalTo( v + "-" + s + sn ) );
    }

    @Test
    public void applySuffixReplaceSNAPSHOT()
        throws Exception
    {
        final Properties props = new Properties();

        final String s = "foo";
        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, s );
        setupSession( props );

        final String originalVersion = "1.0.0.Final-foo-SNAPSHOT";
        final String calcdVersion = "1.0.0.Final-foo-1";

        final String result = calculate( originalVersion );
        assertThat( result, equalTo( calcdVersion ) );
    }

    @Test
    public void applySerialSuffix_InvalidOSGi()
        throws Exception
    {
        final Properties props = new Properties();

        final String origVersion = "1.2.3.4.Final";
        final String suffix = "foo-1";
        final String newVersion = "1.2.3.4-Final-foo-1";

        props.setProperty( VersioningState.VERSION_SUFFIX_SYSPROP, suffix );
        setupSession( props );


        final String result = calculate( origVersion );
        assertThat( result, equalTo( newVersion ) );
    }

    @Test
    public void incrementExistingSerialSuffix()
        throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo-0" );
        setupSession( props );

        final String v = "1.2.0.GA";
        final String os = "-foo-1";
        final String ns = "foo-1";

        final String result = calculate( v + os );
        assertThat( result, equalTo( v + "-" + ns ) );
    }

    @Test
    public void incrementExistingSerialSuffix2()
        throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo-0" );
        setupSession( props, "1.2.0.foo-2" );

        final String v = "1.2.0";
        final String os = ".foo-1";
        final String ns = "foo-3";

        final String result = calculate( v + os );
        assertThat( result, equalTo( v + "." + ns ) );
    }

    @Test
    public void incrementExistingSerialSuffix3()
        throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo" );
        setupSession( props, "1.2.0-foo-1", "1.2.0-foo-2" );

        final String v = "1.2.0.foo-3";

        final String result = calculate( v );
        assertThat( result, equalTo( v ) );
    }

    @Test
    public void incrementExistingSerialSuffix4()
        throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo" );
        setupSession( props, "1.2.0-foo-1", "1.2.0-foo-2" );

        final String v = "1.2.0.foo-4";

        final String result = calculate( v );
        assertThat( result, equalTo( v ) );
    }

    @Test
    public void incrementExistingSerialSuffix5()
        throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo" );
        setupSession( props, "1.2.0-foo-1", "1.2.0-foo-4" );

        final String origVersion = "1.2.0.foo-2";
        final String newVersion = "1.2.0.foo-5";

        final String result = calculate( origVersion );
        assertThat( result, equalTo( newVersion ) );
    }

    @Test
    public void incrementExistingSerialSuffix_CompoundQualifier()
        throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo-bar-0" );
        setupSession( props, "1.2.0.GA-foo-bar-1", "1.2.0.GA-foo-bar-2" );

        final String v = "1.2.0.GA";
        final String os = "-foo-bar-1";
        final String ns = "foo-bar-3";

        final String result = calculate( v + os );
        assertThat( result, equalTo( v + "-" + ns ) );
    }

    @Test
    public void incrementExistingSerialSuffix_InvalidOSGi()
        throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo" );
        setupSession( props, "1.2.0-GA-foo-1" );

        final String origVer = "1.2.0-GA-foo-1";
        final String updatedVer = "1.2.0.GA-foo-2";

        final String result = calculate( origVer );
        assertThat( result, equalTo( updatedVer ) );
    }

    @Test
    public void incrementExistingSerialSuffix_InvalidOSGi_SNAPSHOT()
        throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo" );
        setupSession( props );

        final String origVer = "4.3.3-foo-SNAPSHOT";
        final String updatedVer = "4.3.3.foo-1";

        final String result = calculate( origVer );
        assertThat( result, equalTo( updatedVer ) );
    }

    @Test
    public void incrementExistingSerialSuffix_UsingRepositoryMetadata()
        throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo-0" );
        setupSession( props, "1.2.0.GA-foo-3", "1.2.0.GA-foo-2", "1.2.0.GA-foo-9" );

        final String v = "1.2.0.GA";
        final String os = "-foo-1";
        final String ns = "foo-10";

        final String result = calculate( v + os );
        assertThat( result, equalTo( v + "-" + ns ) );
    }

    @Test
    public void incrementExistingSerialSuffix_withOsgiVersionChangeExistingVersionsMicro()
        throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo" );
        setupSession( props, "1.2-foo-1", "1.2.foo-2" );

        final String v = "1.2";
        final String ns = "foo-3";

        final String result = calculate( v );
        assertThat( result, equalTo( v + ".0." + ns ) );
    }

    @Test
    public void incrementExistingSerialSuffix_TwoProjects_UsingRepositoryMetadata_AvailableOnlyForOne()
        throws Exception
    {
        final String v = "1.2.0.GA";
        final String os = "-foo-1";
        final String ns = "foo-10";

        final Model m1 = new Model();
        m1.setGroupId( GROUP_ID );
        m1.setArtifactId( ARTIFACT_ID );
        m1.setVersion( v + os );
        final Project p1 = new Project( m1 );

        final String a2 = ARTIFACT_ID + "-dep";
        final Model m2 = new Model();
        m2.setGroupId( GROUP_ID );
        m2.setArtifactId( a2 );
        m2.setVersion( v + os );
        final Project p2 = new Project( m2 );

        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo-0" );
        setupSession( props, "1.2.0.GA-foo-3", "1.2.0.GA-foo-2", "1.2.0.GA-foo-9" );

        final Map<ProjectVersionRef, String>
                        result = modder.calculateVersioningChanges( Arrays.asList( p1, p2 ), session );

        assertThat( result.get( new ProjectVersionRef( GROUP_ID, ARTIFACT_ID, v + os ) ), equalTo( v + "-" + ns ) );
        assertThat( result.get( new ProjectVersionRef( GROUP_ID, a2, v + os ) ), equalTo( v + "-" + ns ) );
    }

    @Test
    public void incrementExistingSerialSuffix_TwoProjects_UsingRepositoryMetadata_DifferentAvailableIncrements()
        throws Exception
    {
        final String v = "1.2.0.GA";
        final String os = "-foo-1";
        final String ns = "foo-10";

        final Model m1 = new Model();
        m1.setGroupId( GROUP_ID );
        m1.setArtifactId( ARTIFACT_ID );
        m1.setVersion( v + os );
        final Project p1 = new Project( m1 );

        final String a2 = ARTIFACT_ID + "-dep";
        final Model m2 = new Model();
        m2.setGroupId( GROUP_ID );
        m2.setArtifactId( a2 );
        m2.setVersion( v + os );
        final Project p2 = new Project( m2 );

        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo-0" );
        final Map<ProjectRef, String[]> versionMap = new HashMap<ProjectRef, String[]>();

        versionMap.put( new ProjectRef( p1.getGroupId(), p1.getArtifactId() ), new String[] { "1.2.0.GA-foo-3",
            "1.2.0.GA-foo-2", "1.2.0.GA-foo-9" } );
        versionMap.put( new ProjectRef( p2.getGroupId(), p2.getArtifactId() ), new String[] { "1.2.0.GA-foo-3",
            "1.2.0.GA-foo-2" } );

        setupSession( props, versionMap );

        final Map<ProjectVersionRef, String>
                        result = modder.calculateVersioningChanges( Arrays.asList( p1, p2 ), session );

        assertThat( result.get( new ProjectVersionRef( GROUP_ID, ARTIFACT_ID, v + os ) ), equalTo( v + "-" + ns ) );
        assertThat( result.get( new ProjectVersionRef( GROUP_ID, a2, v + os ) ), equalTo( v + "-" + ns ) );
    }

    @Test
    public void incrementExistingSerialSuffix_UsingRepositoryMetadataWithIrrelevantVersions()
        throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "redhat-0" );
        setupSession( props, "0.0.1", "0.0.2", "0.0.3", "0.0.4", "0.0.5", "0.0.6", "0.0.7", "0.0.7.redhat-1" );

        final String v = "0.0.7";
        //        final String os = "-redhat-2";
        final String ns = "redhat-2";

        final String result = calculate( v );
        assertThat( result, equalTo( v + "." + ns ) );
    }

    @Test
    public void incrementExistingSerialSuffix_Property()
        throws Exception
    {
        final Properties props = new Properties();

        props.setProperty( VersioningState.INCREMENT_SERIAL_SUFFIX_SYSPROP, "foo-0" );
        setupSession( props );

        final String v = "${property}";
        final String os = "-foo-1";
        final String ns = "foo-1";

        final String result = calculate( v + os );
        assertThat( result, equalTo( v + "-" + ns ) );
    }


    private byte[] setupMetadataVersions( final String... versions )
        throws IOException
    {
        final Metadata md = new Metadata();
        final Versioning v = new Versioning();
        md.setVersioning( v );
        v.setVersions( Arrays.asList( versions ) );

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new MetadataXpp3Writer().write( baos, md );

        return baos.toByteArray();
    }

    private String calculate( final String version )
        throws Exception
    {
        Version modifiedVersion = modder.calculate( GROUP_ID, ARTIFACT_ID, version, session );
        if ( session.getState( VersioningState.class ).osgi() )
        {
            return modifiedVersion.getOSGiVersionString(); 
        }
        else
        {
            return modifiedVersion.getVersionString();
        }
    }

    private VersioningState setupSession( final Properties properties, final String... versions )
        throws Exception
    {
        return setupSession( properties, Collections.singletonMap( new ProjectRef( GROUP_ID, ARTIFACT_ID ), versions ) );
    }

    private VersioningState setupSession( final Properties properties, final Map<ProjectRef, String[]> versionMap )
        throws Exception
    {
        final ArtifactRepository ar =
            new MavenArtifactRepository( "test", "http://repo.maven.apache.org/maven2", new DefaultRepositoryLayout(),
                                         new ArtifactRepositoryPolicy(), new ArtifactRepositoryPolicy() );

        final MavenExecutionRequest req =
            new DefaultMavenExecutionRequest().setUserProperties( properties )
                                              .setRemoteRepositories( Arrays.asList( ar ) );

        final PlexusContainer container = new DefaultPlexusContainer();

        final MavenSession mavenSession = new MavenSession( container, null, req, new DefaultMavenExecutionResult() );

        session = new ManipulationSession();
        session.setMavenSession( mavenSession );

        final VersioningState state = new VersioningState( properties );
        session.setState( state );

        final Map<String, byte[]> dataMap = new HashMap<String, byte[]>();
        if ( versionMap != null && !versionMap.isEmpty() )
        {
            for ( final Map.Entry<ProjectRef, String[]> entry : versionMap.entrySet() )
            {
                final String path = toMetadataPath( entry.getKey() );
                final byte[] data = setupMetadataVersions( entry.getValue() );
                dataMap.put( path, data );
            }
        }

        final Location mdLoc = MavenLocationExpander.EXPANSION_TARGET;
        final Transport mdTrans = new StubTransport( dataMap );

        modder =
            new TestVersionCalculator( new ManipulationSession(), mdLoc, mdTrans, temp.newFolder( "galley-cache" ) );

        return state;
    }

    private String toMetadataPath( final ProjectRef key )
    {
        return String.format( "%s/%s/maven-metadata.xml", key.getGroupId()
                                                             .replace( '.', '/' ), key.getArtifactId() );
    }

    public static final class TestVersionCalculator
        extends VersionCalculator
    {

        public TestVersionCalculator( final ManipulationSession session )
            throws ManipulationException
        {
            super( new GalleyAPIWrapper( new GalleyInfrastructure( session.getTargetDir(), session.getRemoteRepositories(),
                                                                   session.getLocalRepository(), session.getSettings(), session.getActiveProfiles() ) ) );
        }

        public TestVersionCalculator( final ManipulationSession session, final Location mdLoc, final Transport mdTrans,
                                      final File cacheDir )
            throws ManipulationException
        {
            super( new GalleyAPIWrapper( new GalleyInfrastructure( session.getTargetDir(), session.getRemoteRepositories(),
                                                                   session.getLocalRepository(), session.getSettings(), session.getActiveProfiles(), new DefaultMirrorSelector(), mdLoc,
                                                                   mdTrans, cacheDir ) ) );
        }

        @Override
        public Version calculate( final String groupId, final String artifactId,
                                             final String originalVersion, final ManipulationSession session )
            throws ManipulationException
        {
            return super.calculate( groupId, artifactId, originalVersion, session );
        }

    }

}
