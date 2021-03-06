/**
 *  Copyright (C) 2015 Red Hat, Inc (jcasey@redhat.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.commonjava.maven.ext.manip;

import groovy.lang.Binding;
import groovy.util.GroovyScriptEngine;
import org.commonjava.maven.ext.manip.invoker.DefaultExecutionParser;
import org.commonjava.maven.ext.manip.invoker.Execution;
import org.commonjava.maven.ext.manip.invoker.ExecutionParser;
import org.commonjava.maven.ext.manip.invoker.Utils;

import java.io.*;
import java.util.*;

import static org.junit.Assert.*;

/**
 * @author vdedik@redhat.com
 */
public class CliTestUtils
{
    public static final String BUILD_DIR = System.getProperty( "buildDirectory" );

    public static final String MVN_LOCATION = System.getProperty("maven.home");

    public static final String IT_LOCATION = BUILD_DIR + "/it-cli";

    public static final String LOCAL_REPO = System.getProperty( "localRepositoryPath" );

    public static final Map<String, String> DEFAULT_MVN_PARAMS = new HashMap<String, String>()
    {{
            put( "maven.repo.local", LOCAL_REPO );
        }};

    /**
     * Run the same/similar execution to what invoker plugin would run.
     *
     * @param workingDir - Working directory where the invoker properties are.
     * @throws Exception
     */
    public static void runLikeInvoker( String workingDir )
        throws Exception
    {
        ExecutionParser executionParser = new DefaultExecutionParser( DefaultExecutionParser.DEFAULT_HANDLERS );
        Collection<Execution> executions = executionParser.parse( workingDir );

        // Execute
        for ( Execution e : executions )
        {
            List<String> args = new ArrayList<String>();
            args.add( "-s" );
            args.add( getDefaultTestLocation( "settings.xml" ) );
            args.add( "-d" );

            // Run PME-Cli
            Integer cliExitValue = runCli( args, e.getJavaParams(), e.getLocation() );

            // Run Maven
            Map<String, String> mavenParams = new HashMap<String, String>();
            mavenParams.putAll( DEFAULT_MVN_PARAMS );
            mavenParams.putAll( e.getJavaParams() );
            Integer mavenExitValue = runMaven( e.getMvnCommand(), mavenParams, e.getLocation() );

            // Test return codes
            if ( e.isSuccess() )
            {
                assertEquals( "PME-Cli exited with a non zero value.", Integer.valueOf( 0 ), cliExitValue );
                assertEquals( "Maven exited with a non zero value.", Integer.valueOf( 0 ), mavenExitValue );
            }
            else
            {
                assertTrue( "Exit value of either PME-Cli or Maven must be non-zero.",
                            cliExitValue != 0 || mavenExitValue != 0 );
            }
        }

        // Verify
        verify( workingDir );
    }

    /**
     * Run pom-manipulation-cli.jar with java params (-D arguments) in workingDir directory.
     * Using test.properties in workingDir as -D arguments.
     *
     * @param workingDir - Working directory in which you want the cli to be run.
     * @return Exit value
     * @throws Exception
     */
    public static Integer runCli( String workingDir )
        throws Exception
    {
        return runCli( new ArrayList<String>(), workingDir );
    }

    /**
     * Run pom-manipulation-cli.jar with java params (-D arguments) in workingDir directory.
     * Using test.properties in workingDir as -D arguments.
     *
     * @param args - List of additional command line arguments
     * @param workingDir - Working directory in which you want the cli to be run.
     * @return Exit value
     * @throws Exception
     */
    public static Integer runCli( List<String> args, String workingDir )
        throws Exception
    {
        Properties testProperties = Utils.loadProps( workingDir + "/test.properties" );
        Map<String, String> javaParams = Utils.propsToMap( testProperties );
        return runCli( args, javaParams, workingDir );
    }

    /**
     * Run pom-manipulation-cli.jar with java params (-D arguments) in workingDir directory.
     *
     * @param params - Map of String keys and String values representing -D arguments
     * @param workingDir - Working directory in which you want the cli to be run.
     * @return Exit value
     * @throws Exception
     */
    public static Integer runCli( Map<String, String> params, String workingDir )
        throws Exception
    {
        return runCli( null, params, workingDir );
    }

    /**
     * Run pom-manipulation-cli.jar with java params (-D arguments) in workingDir directory.
     *
     * @param args - List of additional command line arguments
     * @param params - Map of String keys and String values representing -D arguments
     * @param workingDir - Working directory in which you want the cli to be run.
     * @return Exit value
     * @throws Exception
     */
    public static Integer runCli( List<String> args, Map<String, String> params, String workingDir )
        throws Exception
    {
        String stringArgs = toArguments( args );
        String stringParams = toJavaParams( params );
        String command =
            String.format( "java -jar %s/pom-manipulation-cli.jar %s %s", BUILD_DIR, stringParams, stringArgs );

        return runCommandAndWait( command, workingDir, null );
    }

    /**
     * Run maven process with commands in workingDir directory.
     *
     * @param commands - String representing maven command(s), e.g. "clean install".
     * @param workingDir - Working directory.
     * @return Exit value
     * @throws Exception
     */
    public static Integer runMaven( String commands, String workingDir )
        throws Exception
    {
        return runMaven( commands, null, workingDir );
    }

    /**
     * Run maven process with commands and params (-D arguments) in workingDir directory.
     *
     * @param commands - String representing maven command(s), e.g. "clean install".
     * @param params - Map of String keys and values representing -D arguments.
     * @param workingDir - Working directory.
     * @return Exit value
     * @throws Exception
     */
    public static Integer runMaven( String commands, Map<String, String> params, String workingDir )
        throws Exception
    {
        String stringParams = toJavaParams( params );
        String commandMaven = String.format( "mvn %s %s ", commands, stringParams );

        return runCommandAndWait(commandMaven, workingDir, MVN_LOCATION + "/bin");
    }

    /**
     * Run verify.groovy script in workingDir directory.
     *
     * @param workingDir - Directory with verify.groovy script.
     * @throws Exception
     */
    public static void verify( String workingDir )
        throws Exception
    {
        File verify = new File( workingDir + "/verify.groovy" );
        if ( !verify.isFile() )
        {
            return;
        }
        Binding binding = new Binding();
        binding.setVariable( "basedir", workingDir );
        GroovyScriptEngine engine = new GroovyScriptEngine( workingDir );
        engine.run( "verify.groovy", binding );
    }

    /**
     * Get default location of integration test by test name.
     *
     * @param test - Test name.
     * @return Default location of integration test, e.g. ~/pom-manipulation-ext/integration-test/target/it-cli/it-test
     */
    public static String getDefaultTestLocation( String test )
    {
        return String.format( "%s/%s", IT_LOCATION, test );
    }

    /**
     * Convert string parameters in a Map to a String of -D arguments
     *
     * @param params - Map of java parameters
     * @return - String of -D arguments
     */
    public static String toJavaParams( Map<String, String> params )
    {
        if ( params == null )
        {
            return "";
        }

        String stringParams = "";
        for ( String key : params.keySet() )
        {
            stringParams += String.format( "-D%s=%s ", key, params.get( key ) );
        }
        return stringParams;
    }

    /**
     * Convert string arguments in a List to a String
     *
     * @param args - List of command line options with its arguments
     * @return - String of options with it's arguments
     */
    public static String toArguments( List<String> args )
    {
        if ( args == null )
        {
            return "";
        }

        String stringArgs = "";
        for ( String arg : args )
        {
            stringArgs += String.format( "%s ", arg );
        }
        return stringArgs;
    }

    /**
     * Run command in another process and wait for it to finish.
     *
     * @param command - Command to be run in another process, e.g. "mvn clean install"
     * @param workingDir - Working directory in which to run the command.
     * @param s
     * @return exit value.
     * @throws Exception
     */
    public static Integer runCommandAndWait( String command, String workingDir, String extraPath )
        throws Exception
    {
        String path = System.getenv( "PATH" );
        if (extraPath != null)
        {
            path = extraPath + System.getProperty("path.separator") + path;
        }

        Process proc = Runtime.getRuntime().exec(command, new String[] {"M2_HOME=" + MVN_LOCATION, "PATH=" + path}, new File(workingDir));
        File buildlog = new File(workingDir + "/build.log");

        BufferedReader stdout = new BufferedReader( new InputStreamReader( proc.getInputStream() ) );
        BufferedReader stderr = new BufferedReader( new InputStreamReader( proc.getErrorStream() ) );
        PrintWriter out = new PrintWriter( new BufferedWriter( new FileWriter( buildlog, true ) ) );

        String line = null;
        String errline = null;
        while ( ( line = stdout.readLine() ) != null || ( errline = stderr.readLine() ) != null )
        {
            if ( line != null )
            {
                out.println( line );
            }
            if ( errline != null )
            {
                out.println( errline );
            }
        }

        stdout.close();
        stderr.close();
        out.close();

        return proc.waitFor();
    }
}
