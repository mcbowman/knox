/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.gateway;

import com.jayway.restassured.http.ContentType;
import com.jayway.restassured.response.Cookie;
import com.jayway.restassured.response.Response;
import com.jayway.restassured.specification.ResponseSpecification;
import com.mycila.xmltool.XMLDoc;
import com.mycila.xmltool.XMLTag;

import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.hadoop.test.TestUtils;
import org.apache.hadoop.test.category.FunctionalTests;
import org.apache.hadoop.test.category.MediumTests;
import org.apache.hadoop.test.log.NoOpLogger;
import org.apache.hadoop.test.mock.MockRequestMatcher;
import org.apache.http.HttpStatus;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.eclipse.jetty.util.log.Log;
import org.hamcrest.Matcher;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import static com.jayway.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.xmlmatchers.XmlMatchers.isEquivalentTo;
import static org.xmlmatchers.transform.XmlConverters.the;
import static uk.co.datumedge.hamcrest.json.SameJSONAs.sameJSONAs;
import static org.hamcrest.text.IsEmptyString.isEmptyString;

@Category( { FunctionalTests.class, MediumTests.class } )
public class GatewayBasicFuncTest {

  private static final Charset UTF8 = Charset.forName("UTF-8");

  // Uncomment to cause the test to hang after the gateway instance is setup.
  // This will allow the gateway instance to be hit directly via some external client.
//  @Test
//  public void hang() throws IOException {
//    System.out.println( "Server on port " + driver.gateway.getAddresses()[0].getPort() );
//    System.out.println();
//    System.in.read();
//  }

  private static Logger log = LoggerFactory.getLogger( GatewayBasicFuncTest.class );

  public static GatewayFuncTestDriver driver = new GatewayFuncTestDriver();

  // Controls the host name to which the gateway dispatch requests.  This may be the name of a sandbox VM
  // or an EC2 instance.  Currently only a single host is supported.
  private static final String TEST_HOST = "vm.local";

  // Specifies if the test requests should go through the gateway or directly to the services.
  // This is frequently used to verify the behavior of the test both with and without the gateway.
  private static final boolean USE_GATEWAY = true;

  // Specifies if the test requests should be sent to mock services or the real services.
  // This is frequently used to verify the behavior of the test both with and without mock services.
  private static final boolean USE_MOCK_SERVICES = true;

  // Specifies if the GATEWAY_HOME created for the test should be deleted when the test suite is complete.
  // This is frequently used during debugging to keep the GATEWAY_HOME around for inspection.
  private static final boolean CLEANUP_TEST = true;

//  private static final boolean USE_GATEWAY = false;
//  private static final boolean USE_MOCK_SERVICES = false;
//  private static final boolean CLEANUP_TEST = false;

  private static int findFreePort() throws IOException {
    ServerSocket socket = new ServerSocket(0);
    int port = socket.getLocalPort();
    socket.close();
    return port;
  }

  /**
   * Creates a deployment of a gateway instance that all test methods will share.  This method also creates a
   * registry of sorts for all of the services that will be used by the test methods.
   * The createTopology method is used to create the topology file that would normally be read from disk.
   * The driver.setupGateway invocation is where the creation of GATEWAY_HOME occurs.
   * @throws Exception Thrown if any failure occurs.
   */
  @BeforeClass
  public static void setupSuite() throws Exception {
    Log.setLog( new NoOpLogger() );
    GatewayTestConfig config = new GatewayTestConfig();
    config.setGatewayPath( "gateway" );
    driver.setResourceBase( GatewayBasicFuncTest.class );
    driver.setupLdap( findFreePort() );
    driver.setupService( "WEBHDFS", "http://" + TEST_HOST + ":50070/webhdfs", "/cluster/webhdfs", USE_MOCK_SERVICES );
    driver.setupService( "DATANODE", "http://" + TEST_HOST + ":50075/webhdfs", "/cluster/webhdfs/data", USE_MOCK_SERVICES );
    driver.setupService( "WEBHCAT", "http://" + TEST_HOST + ":50111/templeton", "/cluster/templeton", USE_MOCK_SERVICES );
    driver.setupService( "OOZIE", "http://" + TEST_HOST + ":11000/oozie", "/cluster/oozie", USE_MOCK_SERVICES );
    driver.setupService( "HIVE", "http://" + TEST_HOST + ":10000", "/cluster/hive", USE_MOCK_SERVICES );
    driver.setupService( "WEBHBASE", "http://" + TEST_HOST + ":60080", "/cluster/hbase", USE_MOCK_SERVICES );
    driver.setupService( "NAMENODE", "hdfs://" + TEST_HOST + ":8020", null, USE_MOCK_SERVICES );
    driver.setupService( "JOBTRACKER", "thrift://" + TEST_HOST + ":8021", null, USE_MOCK_SERVICES );
    driver.setupService( "RESOURCEMANAGER", "http://" + TEST_HOST + ":8088/ws", "/cluster/resourcemanager", USE_MOCK_SERVICES );
    driver.setupGateway( config, "cluster", createTopology(), USE_GATEWAY );
  }

  @AfterClass
  public static void cleanupSuite() throws Exception {
    if( CLEANUP_TEST ) {
      driver.cleanup();
    }
  }

  @After
  public void cleanupTest() {
    driver.reset();
  }

  /**
   * Creates a topology that is deployed to the gateway instance for the test suite.
   * Note that this topology is shared by all of the test methods in this suite.
   * @return A populated XML structure for a topology file.
   */
  private static XMLTag createTopology() {
    XMLTag xml = XMLDoc.newDocument( true )
        .addRoot( "topology" )
          .addTag( "gateway" )
            .addTag( "provider" )
              .addTag( "role" ).addText( "webappsec" )
              .addTag( "name" ).addText( "WebAppSec" )
              .addTag( "enabled" ).addText( "true" )
              .addTag( "param" )
                .addTag( "name" ).addText( "csrf.enabled" )
                .addTag( "value" ).addText( "true" ).gotoParent().gotoParent()
            .addTag( "provider" )
              .addTag( "role" ).addText( "authentication" )
              .addTag( "name" ).addText( "ShiroProvider" )
              .addTag( "enabled" ).addText( "true" )
              .addTag( "param" )
                .addTag( "name" ).addText( "main.ldapRealm" )
                .addTag( "value" ).addText( "org.apache.hadoop.gateway.shirorealm.KnoxLdapRealm" ).gotoParent()
              .addTag( "param" )
                .addTag( "name" ).addText( "main.ldapRealm.userDnTemplate" )
                .addTag( "value" ).addText( "uid={0},ou=people,dc=hadoop,dc=apache,dc=org" ).gotoParent()
              .addTag( "param" )
                .addTag( "name" ).addText( "main.ldapRealm.contextFactory.url" )
                .addTag( "value" ).addText( driver.getLdapUrl() ).gotoParent()
              .addTag( "param" )
                .addTag( "name" ).addText( "main.ldapRealm.contextFactory.authenticationMechanism" )
                .addTag( "value" ).addText( "simple" ).gotoParent()
              .addTag( "param" )
                .addTag( "name" ).addText( "urls./**" )
                .addTag( "value" ).addText( "authcBasic" ).gotoParent().gotoParent()
            .addTag( "provider" )
              .addTag( "role" ).addText( "identity-assertion" )
              .addTag( "enabled" ).addText( "true" )
              .addTag( "name" ).addText( "Default" ).gotoParent()
            .addTag( "provider" )
              .addTag( "role" ).addText( "authorization" )
              .addTag( "enabled" ).addText( "true" )
              .addTag( "name" ).addText( "AclsAuthz" ).gotoParent()
              .addTag( "param" )
                .addTag( "name" ).addText( "webhdfs-acl" )
                .addTag( "value" ).addText( "hdfs;*;*" ).gotoParent()
          .gotoRoot()
          .addTag( "service" )
            .addTag( "role" ).addText( "WEBHDFS" )
            .addTag( "url" ).addText( driver.getRealUrl( "WEBHDFS" ) ).gotoParent()
          .addTag( "service" )
            .addTag( "role" ).addText( "NAMENODE" )
            .addTag( "url" ).addText( driver.getRealUrl( "NAMENODE" ) ).gotoParent()
          .addTag( "service" )
            .addTag( "role" ).addText( "DATANODE" )
            .addTag( "url" ).addText( driver.getRealUrl( "DATANODE" ) ).gotoParent()
          .addTag( "service" )
            .addTag( "role" ).addText( "JOBTRACKER" )
            .addTag( "url" ).addText( driver.getRealUrl( "JOBTRACKER" ) ).gotoParent()
          .addTag( "service" )
            .addTag( "role" ).addText( "WEBHCAT" )
            .addTag( "url" ).addText( driver.getRealUrl( "WEBHCAT" ) ).gotoParent()
          .addTag( "service" )
            .addTag( "role" ).addText( "OOZIE" )
            .addTag( "url" ).addText( driver.getRealUrl( "OOZIE" ) ).gotoParent()
          .addTag( "service" )
            .addTag( "role" ).addText( "HIVE" )
            .addTag( "url" ).addText( driver.getRealUrl( "HIVE" ) ).gotoParent()
          .addTag( "service" )
            .addTag( "role" ).addText( "WEBHBASE" )
            .addTag( "url" ).addText( driver.getRealUrl( "WEBHBASE" ) ).gotoParent()
        .addTag( "service" )
            .addTag( "role" ).addText( "RESOURCEMANAGER" )
            .addTag( "url" ).addText( driver.getRealUrl( "RESOURCEMANAGER" ) ).gotoParent()
        .gotoRoot();
//     System.out.println( "GATEWAY=" + xml.toString() );
    return xml;
  }

  @Test
  public void testBasicJsonUseCase() throws IOException {
    String root = "/tmp/GatewayBasicFuncTest/testBasicJsonUseCase";
    String username = "hdfs";
    String password = "hdfs-password";
    /* Create a directory.
    curl -i -X PUT "http://<HOST>:<PORT>/<PATH>?op=MKDIRS[&permission=<OCTAL>]"

    The client receives a respond with a boolean JSON object:
    HTTP/1.1 HttpStatus.SC_OK OK
    Content-Type: application/json
    Transfer-Encoding: chunked

    {"boolean": true}
    */
    driver.getMock( "WEBHDFS" )
        .expect()
        .method( "PUT" )
        .pathInfo( "/v1" + root + "/dir" )
        .queryParam( "op", "MKDIRS" )
        .queryParam( "user.name", username )
        .respond()
        .status( HttpStatus.SC_OK )
        .content( driver.getResourceBytes( "webhdfs-success.json" ) )
        .contentType( "application/json" );
    Cookie cookie = given()
        //.log().all()
        .auth().preemptive().basic( username, password )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .queryParam( "op", "MKDIRS" )
        .expect()
        //.log().all()
        .statusCode( HttpStatus.SC_OK )
        .header( "Set-Cookie", containsString( "JSESSIONID" ) )
        .header( "Set-Cookie", containsString( "HttpOnly" ) )
        .contentType( "application/json" )
        .content( "boolean", is( true ) )
        .when().put( driver.getUrl( "WEBHDFS" ) + "/v1" + root + "/dir" ).getDetailedCookie( "JSESSIONID" );
    assertThat( cookie.isSecured(), is( true ) );
    assertThat( cookie.getPath(), is( "/gateway/cluster" ) );
    assertThat( cookie.getValue().length(), greaterThan( 16 ) );
    driver.assertComplete();
  }

  @Test
  public void testBasicOutboundHeaderUseCase() throws IOException {
    String root = "/tmp/GatewayBasicFuncTest/testBasicOutboundHeaderUseCase";
    String username = "hdfs";
    String password = "hdfs-password";
    InetSocketAddress gatewayAddress = driver.gateway.getAddresses()[0];
    String gatewayHostName = gatewayAddress.getHostName();
    String gatewayAddrName = InetAddress.getByName( gatewayHostName ).getHostAddress();

    driver.getMock( "WEBHDFS" )
        .expect()
        .method( "PUT" )
        .pathInfo( "/v1" + root + "/dir/file" )
        .header( "Host", driver.getRealAddr( "WEBHDFS" ) )
        .queryParam( "op", "CREATE" )
        .queryParam( "user.name", username )
        .respond()
        .status( HttpStatus.SC_TEMPORARY_REDIRECT )
        .header( "Location", driver.getRealUrl( "DATANODE" ) + "/v1" + root + "/dir/file?op=CREATE&user.name=hdfs" );
    Response response = given()
        //.log().all()
        .auth().preemptive().basic( username, password )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .queryParam( "op", "CREATE" )
        .expect()
        //.log().ifError()
        .statusCode( HttpStatus.SC_TEMPORARY_REDIRECT )
        .when().put( driver.getUrl("WEBHDFS") + "/v1" + root + "/dir/file" );
    String location = response.getHeader( "Location" );
    //System.out.println( location );
    log.debug( "Redirect location: " + response.getHeader( "Location" ) );
    if( driver.isUseGateway() ) {
      MatcherAssert.assertThat( location, anyOf(
          startsWith( "http://" + gatewayHostName + ":" + gatewayAddress.getPort() + "/" ),
          startsWith( "http://" + gatewayAddrName + ":" + gatewayAddress.getPort() + "/" ) ) );
      MatcherAssert.assertThat( location, containsString( "?_=" ) );
    }
    MatcherAssert.assertThat( location, not( containsString( "host=" ) ) );
    MatcherAssert.assertThat( location, not( containsString( "port=" ) ) );
  }

  @Test
  public void testHdfsTildeUseCase() throws IOException {
    String root = "/tmp/GatewayBasicFuncTest/testHdfsTildeUseCase";
    String username = "hdfs";
    String password = "hdfs-password";
    InetSocketAddress gatewayAddress = driver.gateway.getAddresses()[0];

    // Attempt to delete the test directory in case a previous run failed.
    // Ignore any result.
    // Cleanup anything that might have been leftover because the test failed previously.
    driver.getMock( "WEBHDFS" )
        .expect()
        .method( "DELETE" )
        .from( "testHdfsTildeUseCase" )
        .pathInfo( "/v1/user/hdfs" + root )
        .queryParam( "op", "DELETE" )
        .queryParam( "user.name", username )
        .queryParam( "recursive", "true" )
        .respond()
        .status( HttpStatus.SC_OK );
    given()
        .auth().preemptive().basic( username, password )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .queryParam( "op", "DELETE" )
        .queryParam( "recursive", "true" )
        .expect()
            //.log().all();
        .statusCode( HttpStatus.SC_OK )
        .when().delete( driver.getUrl( "WEBHDFS" ) + "/v1/~" + root + ( driver.isUseGateway() ? "" : "?user.name=" + username ) );
    driver.assertComplete();

    driver.getMock( "WEBHDFS" )
        .expect()
        .method( "PUT" )
        .pathInfo( "/v1/user/hdfs/dir" )
        .queryParam( "op", "MKDIRS" )
        .queryParam( "user.name", username )
        .respond()
        .status( HttpStatus.SC_OK )
        .content( driver.getResourceBytes( "webhdfs-success.json" ) )
        .contentType( "application/json" );
    given()
        //.log().all()
        .auth().preemptive().basic( username, password )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .queryParam( "op", "MKDIRS" )
        .expect()
            //.log().all();
        .statusCode( HttpStatus.SC_OK )
        .contentType( "application/json" )
        .content( "boolean", is( true ) )
        .when().put( driver.getUrl( "WEBHDFS" ) + "/v1/~/dir" );
    driver.assertComplete();

  }

  @Test
  public void testBasicHdfsUseCase() throws IOException {
    String root = "/tmp/GatewayBasicFuncTest/testBasicHdfsUseCase";
    String username = "hdfs";
    String password = "hdfs-password";
    InetSocketAddress gatewayAddress = driver.gateway.getAddresses()[0];
    String gatewayHostName = gatewayAddress.getHostName();
    String gatewayAddrName = InetAddress.getByName( gatewayHostName ).getHostAddress();

    // Attempt to delete the test directory in case a previous run failed.
    // Ignore any result.
    // Cleanup anything that might have been leftover because the test failed previously.
    driver.getMock( "WEBHDFS" )
        .expect()
        .method( "DELETE" )
        .from( "testBasicHdfsUseCase-1" )
        .pathInfo( "/v1" + root )
        .queryParam( "op", "DELETE" )
        .queryParam( "user.name", username )
        .queryParam( "recursive", "true" )
        .respond()
        .status( HttpStatus.SC_OK );
    given()
        //.log().all()
        .auth().preemptive().basic( username, password )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .queryParam( "op", "DELETE" )
        .queryParam( "recursive", "true" )
        .expect()
        .log().all()
        .statusCode( HttpStatus.SC_OK )
        .when().delete( driver.getUrl( "WEBHDFS" ) + "/v1" + root + ( driver.isUseGateway() ? "" : "?user.name=" + username ) );
    driver.assertComplete();

    /* Create a directory.
    curl -i -X PUT "http://<HOST>:<PORT>/<PATH>?op=MKDIRS[&permission=<OCTAL>]"

    The client receives a respond with a boolean JSON object:
    HTTP/1.1 HttpStatus.SC_OK OK
    Content-Type: application/json
    Transfer-Encoding: chunked

    {"boolean": true}
    */
    driver.getMock( "WEBHDFS" )
        .expect()
        .method( "PUT" )
        .pathInfo( "/v1" + root + "/dir" )
        .queryParam( "op", "MKDIRS" )
        .queryParam( "user.name", username )
        .respond()
        .status( HttpStatus.SC_OK )
        .content( driver.getResourceBytes( "webhdfs-success.json" ) )
        .contentType( "application/json" );
    given()
        //.log().all()
        .auth().preemptive().basic( username, password )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .queryParam( "op", "MKDIRS" )
        .expect()
        //.log().all();
        .statusCode( HttpStatus.SC_OK )
        .contentType( "application/json" )
        .content( "boolean", is( true ) )
        .when().put( driver.getUrl( "WEBHDFS" ) + "/v1" + root + "/dir" );
    driver.assertComplete();

    driver.getMock( "WEBHDFS" )
        .expect()
        .method( "GET" )
        .pathInfo( "/v1" + root )
        .queryParam( "op", "LISTSTATUS" )
        .queryParam( "user.name", username )
        .respond()
        .status( HttpStatus.SC_OK )
        .content( driver.getResourceBytes( "webhdfs-liststatus-test.json" ) )
        .contentType( "application/json" );
    given()
        //.log().all()
        .auth().preemptive().basic( username, password )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .queryParam( "op", "LISTSTATUS" )
        .expect()
        //.log().ifError()
        .statusCode( HttpStatus.SC_OK )
        .content( "FileStatuses.FileStatus[0].pathSuffix", is( "dir" ) )
        .when().get( driver.getUrl( "WEBHDFS" ) + "/v1" + root );
    driver.assertComplete();

    //NEGATIVE: Test a bad password.
    given()
        //.log().all()
        .auth().preemptive().basic( username, "invalid-password" )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .queryParam( "op", "LISTSTATUS" )
        .expect()
        //.log().ifError()
        .statusCode( HttpStatus.SC_UNAUTHORIZED )
        .when().get( driver.getUrl( "WEBHDFS" ) + "/v1" + root );
    driver.assertComplete();

    //NEGATIVE: Test a bad user.
    given()
        //.log().all()
        .auth().preemptive().basic( "hdfs-user", "hdfs-password" )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .queryParam( "op", "LISTSTATUS" )
        .expect()
        //.log().ifError()
        .statusCode( HttpStatus.SC_UNAUTHORIZED )
        .when().get( driver.getUrl( "WEBHDFS" ) + "/v1" + root );
    driver.assertComplete();

    //NEGATIVE: Test a valid but unauthorized user.
    given()
      //.log().all()
      .auth().preemptive().basic( "mapred-user", "mapred-password" )
      .header("X-XSRF-Header", "jksdhfkhdsf")
      .queryParam( "op", "LISTSTATUS" )
      .expect()
      //.log().ifError()
      .statusCode( HttpStatus.SC_UNAUTHORIZED )
      .when().get( driver.getUrl( "WEBHDFS" ) + "/v1" + root );

    /* Add a file.
    curl -i -X PUT "http://<HOST>:<PORT>/webhdfs/v1/<PATH>?op=CREATE
                       [&overwrite=<true|false>][&blocksize=<LONG>][&replication=<SHORT>]
                     [&permission=<OCTAL>][&buffersize=<INT>]"

    The expect is redirected to a datanode where the file data is to be written:
    HTTP/1.1 307 TEMPORARY_REDIRECT
    Location: http://<DATANODE>:<PORT>/webhdfs/v1/<PATH>?op=CREATE...
    Content-Length: 0

    Step 2: Submit another HTTP PUT expect using the URL in the Location header with the file data to be written.
    curl -i -X PUT -T <LOCAL_FILE> "http://<DATANODE>:<PORT>/webhdfs/v1/<PATH>?op=CREATE..."

    The client receives a HttpStatus.SC_CREATED Created respond with zero content length and the WebHDFS URI of the file in the Location header:
    HTTP/1.1 HttpStatus.SC_CREATED Created
    Location: webhdfs://<HOST>:<PORT>/<PATH>
    Content-Length: 0
    */
    driver.getMock( "WEBHDFS" )
        .expect()
        .method( "PUT" )
        .pathInfo( "/v1" + root + "/dir/file" )
        .queryParam( "op", "CREATE" )
        .queryParam( "user.name", username )
        .respond()
        .status( HttpStatus.SC_TEMPORARY_REDIRECT )
        .header( "Location", driver.getRealUrl( "DATANODE" ) + "/v1" + root + "/dir/file?op=CREATE&user.name=hdfs" );
    driver.getMock( "DATANODE" )
        .expect()
        .method( "PUT" )
        .pathInfo( "/v1" + root + "/dir/file" )
        .queryParam( "op", "CREATE" )
        .queryParam( "user.name", username )
        .contentType( "text/plain" )
        .content( driver.getResourceBytes( "test.txt" ) )
            //.content( driver.gerResourceBytes( "hadoop-examples.jar" ) )
        .respond()
        .status( HttpStatus.SC_CREATED )
        .header( "Location", "webhdfs://" + driver.getRealAddr( "DATANODE" ) + "/v1" + root + "/dir/file" );
    Response response = given()
        //.log().all()
        .auth().preemptive().basic( username, password )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .queryParam( "op", "CREATE" )
        .expect()
        //.log().ifError()
        .statusCode( HttpStatus.SC_TEMPORARY_REDIRECT )
        .when().put( driver.getUrl("WEBHDFS") + "/v1" + root + "/dir/file" );
    String location = response.getHeader( "Location" );
    log.debug( "Redirect location: " + response.getHeader( "Location" ) );
    if( driver.isUseGateway() ) {
      MatcherAssert.assertThat( location, anyOf(
          startsWith( "http://" + gatewayHostName + ":" + gatewayAddress.getPort() + "/" ),
          startsWith( "http://" + gatewayAddrName + ":" + gatewayAddress.getPort() + "/" ) ) );
      MatcherAssert.assertThat( location, containsString( "?_=" ) );
    }
    MatcherAssert.assertThat( location, not( containsString( "host=" ) ) );
    MatcherAssert.assertThat( location, not( containsString( "port=" ) ) );
    response = given()
        //.log().all()
        .auth().preemptive().basic( username, password )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .content( driver.getResourceBytes( "test.txt" ) )
        .contentType( "text/plain" )
        .expect()
        //.log().ifError()
        .statusCode( HttpStatus.SC_CREATED )
        .when().put( location );
    location = response.getHeader( "Location" );
    log.debug( "Created location: " + location );
    if( driver.isUseGateway() ) {
      MatcherAssert.assertThat( location, anyOf(
          startsWith( "http://" + gatewayHostName + ":" + gatewayAddress.getPort() + "/" ),
          startsWith( "http://" + gatewayAddrName + ":" + gatewayAddress.getPort() + "/" ) ) );
    }
    driver.assertComplete();

    /* Get the file.
    curl -i -L "http://<HOST>:<PORT>/webhdfs/v1/<PATH>?op=OPEN
                       [&offset=<LONG>][&length=<LONG>][&buffersize=<INT>]"

    The expect is redirected to a datanode where the file data can be read:
    HTTP/1.1 307 TEMPORARY_REDIRECT
    Location: http://<DATANODE>:<PORT>/webhdfs/v1/<PATH>?op=OPEN...
    Content-Length: 0

    The client follows the redirect to the datanode and receives the file data:
    HTTP/1.1 HttpStatus.SC_OK OK
    Content-Type: application/octet-stream
    Content-Length: 22

    Hello, webhdfs user!
    */
    driver.getMock( "WEBHDFS" )
        .expect()
        .method( "GET" )
        .pathInfo( "/v1" + root + "/dir/file" )
        .queryParam( "op", "OPEN" )
        .queryParam( "user.name", username )
        .respond()
        .status( HttpStatus.SC_TEMPORARY_REDIRECT )
        .header( "Location", driver.getRealUrl( "DATANODE" ) + "/v1" + root + "/dir/file?op=OPEN&user.name=hdfs" );
    driver.getMock( "DATANODE" )
        .expect()
        .method( "GET" )
        .pathInfo( "/v1" + root + "/dir/file" )
        .queryParam( "op", "OPEN" )
        .queryParam( "user.name", username )
        .respond()
        .status( HttpStatus.SC_OK )
        .contentType( "text/plain" )
        .content( driver.getResourceBytes( "test.txt" ) );
    given()
        //.log().all()
        .auth().preemptive().basic( username, password )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .queryParam( "op", "OPEN" )
        .expect()
        //.log().ifError()
        .statusCode( HttpStatus.SC_OK )
        .content( is( "TEST" ) )
        .when().get( driver.getUrl("WEBHDFS") + "/v1" + root + "/dir/file" );
    driver.assertComplete();

    /* Delete the directory.
    curl -i -X DELETE "http://<host>:<port>/webhdfs/v1/<path>?op=DELETE
                                 [&recursive=<true|false>]"

    The client receives a respond with a boolean JSON object:
    HTTP/1.1 HttpStatus.SC_OK OK
    Content-Type: application/json
    Transfer-Encoding: chunked

    {"boolean": true}
    */
    // Mock the interaction with the namenode.
    driver.getMock( "WEBHDFS" )
        .expect()
        .from( "testBasicHdfsUseCase-1" )
        .method( "DELETE" )
        .pathInfo( "/v1" + root )
        .queryParam( "op", "DELETE" )
        .queryParam( "user.name", username )
        .queryParam( "recursive", "true" )
        .respond()
        .status( HttpStatus.SC_OK );
    given()
        .auth().preemptive().basic( username, password )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .queryParam( "op", "DELETE" )
        .queryParam( "recursive", "true" )
        .expect()
        //.log().ifError()
        .statusCode( HttpStatus.SC_OK )
        .when().delete( driver.getUrl( "WEBHDFS" ) + "/v1" + root );
    driver.assertComplete();
  }

  // User hdfs in groups hadoop, hdfs
  // User mapred in groups hadoop, mapred
  // User hcat in group hcat
  @Test
  public void testPmHdfsM1UseCase() throws IOException {
    String root = "/tmp/GatewayBasicFuncTest/testPmHdfdM1UseCase";
    String userA = "hdfs";
    String passA = "hdfs-password";
    String userB = "mapred";
    String passB = "mapred-password";
    String userC = "hcat";
    String passC = "hcat-password";
    String groupA = "hdfs";
    String groupB = "mapred";
    String groupAB = "hadoop";
    String groupC = "hcat";

    driver.deleteFile( userA, passA, root, "true", 200 );

    driver.createDir( userA, passA, groupA, root + "/dirA700", "700", 200, 200 );
    driver.createDir( userA, passA, groupA, root + "/dirA770", "770", 200, 200 );
    driver.createDir( userA, passA, groupA, root + "/dirA707", "707", 200, 200 );
    driver.createDir( userA, passA, groupA, root + "/dirA777", "777", 200, 200 );
    driver.createDir( userA, passA, groupAB, root + "/dirAB700", "700", 200, 200 );
    driver.createDir( userA, passA, groupAB, root + "/dirAB770", "770", 200, 200 );
    driver.createDir( userA, passA, groupAB, root + "/dirAB707", "707", 200, 200 );
    driver.createDir( userA, passA, groupAB, root + "/dirAB777", "777", 200, 200 );

    // CREATE: Files
    // userA:groupA
    driver.createFile( userA, passA, groupA, root + "/dirA700/fileA700", "700", "text/plain", "small1.txt", 307, 201, 200 );
    driver.createFile( userA, passA, groupA, root + "/dirA770/fileA770", "770", "text/plain", "small1.txt", 307, 201, 200 );
    driver.createFile( userA, passA, groupA, root + "/dirA707/fileA707", "707", "text/plain", "small1.txt", 307, 201, 200 );
    driver.createFile( userA, passA, groupA, root + "/dirA777/fileA777", "777", "text/plain", "small1.txt", 307, 201, 200 );
    // userA:groupAB
    driver.createFile( userA, passA, groupAB, root + "/dirAB700/fileAB700", "700", "text/plain", "small1.txt", 307, 201, 200 );
    driver.createFile( userA, passA, groupAB, root + "/dirAB770/fileAB770", "770", "text/plain", "small1.txt", 307, 201, 200 );
    driver.createFile( userA, passA, groupAB, root + "/dirAB707/fileAB707", "707", "text/plain", "small1.txt", 307, 201, 200 );
    driver.createFile( userA, passA, groupAB, root + "/dirAB777/fileAB777", "777", "text/plain", "small1.txt", 307, 201, 200 );
    // userB:groupB
    driver.createFile( userB, passB, groupB, root + "/dirA700/fileB700", "700", "text/plain", "small1.txt", 307, 403, 0 );
    driver.createFile( userB, passB, groupB, root + "/dirA770/fileB700", "700", "text/plain", "small1.txt", 307, 403, 0 );
//kam:20130219[ chmod seems to be broken at least in Sandbox 1.2
//    driver.createFile( userB, passB, groupB, root + "/dirA707/fileB700", "700", "text/plain", "small1.txt", 307, 201, 200 );
//    driver.createFile( userB, passB, groupB, root + "/dirA777/fileB700", "700", "text/plain", "small1.txt", 307, 201, 200 );
//kam]
    // userB:groupAB
    driver.createFile( userB, passB, groupAB, root + "/dirA700/fileBA700", "700", "text/plain", "small1.txt", 307, 403, 0 );
    driver.createFile( userB, passB, groupAB, root + "/dirA770/fileBA700", "700", "text/plain", "small1.txt", 307, 403, 0 );
    driver.createFile( userB, passB, groupAB, root + "/dirA707/fileBA700", "700", "text/plain", "small1.txt", 307, 201, 200 );
    driver.createFile( userB, passB, groupAB, root + "/dirA777/fileBA700", "700", "text/plain", "small1.txt", 307, 201, 200 );
    // userC:groupC
    driver.createFile( userC, passC, groupC, root + "/dirA700/fileC700", "700", "text/plain", "small1.txt", 307, 403, 0 );
    driver.createFile( userC, passC, groupC, root + "/dirA770/fileC700", "700", "text/plain", "small1.txt", 307, 403, 0 );
//kam:20130219[ chmod seems to be broken at least in Sandbox 1.2
//    driver.createFile( userC, passC, groupC, root + "/dirA707/fileC700", "700", "text/plain", "small1.txt", 307, 201, 200 );
//    driver.createFile( userC, passC, groupC, root + "/dirA777/fileC700", "700", "text/plain", "small1.txt", 307, 201, 200 );
//kam]

    // READ
    // userA
    driver.readFile( userA, passA, root + "/dirA700/fileA700", "text/plain", "small1.txt", HttpStatus.SC_OK );
    driver.readFile( userA, passA, root + "/dirA770/fileA770", "text/plain", "small1.txt", HttpStatus.SC_OK );
    driver.readFile( userA, passA, root + "/dirA707/fileA707", "text/plain", "small1.txt", HttpStatus.SC_OK );
    driver.readFile( userA, passA, root + "/dirA777/fileA777", "text/plain", "small1.txt", HttpStatus.SC_OK );
    // userB:groupB
    driver.readFile( userB, passB, root + "/dirA700/fileA700", "text/plain", "small1.txt", HttpStatus.SC_FORBIDDEN );
    driver.readFile( userB, passB, root + "/dirA770/fileA770", "text/plain", "small1.txt", HttpStatus.SC_FORBIDDEN );
    driver.readFile( userB, passB, root + "/dirA707/fileA707", "text/plain", "small1.txt", HttpStatus.SC_OK );
    driver.readFile( userB, passB, root + "/dirA777/fileA777", "text/plain", "small1.txt", HttpStatus.SC_OK );
    // userB:groupAB
    driver.readFile( userB, passB, root + "/dirAB700/fileAB700", "text/plain", "small1.txt", HttpStatus.SC_FORBIDDEN );
    driver.readFile( userB, passB, root + "/dirAB770/fileAB770", "text/plain", "small1.txt", HttpStatus.SC_FORBIDDEN );
    driver.readFile( userB, passB, root + "/dirAB707/fileAB707", "text/plain", "small1.txt", HttpStatus.SC_FORBIDDEN );
    driver.readFile( userB, passB, root + "/dirAB777/fileAB777", "text/plain", "small1.txt", HttpStatus.SC_OK );
    // userC:groupC
    driver.readFile( userC, passC, root + "/dirA700/fileA700", "text/plain", "small1.txt", HttpStatus.SC_FORBIDDEN );
    driver.readFile( userC, passC, root + "/dirA770/fileA770", "text/plain", "small1.txt", HttpStatus.SC_FORBIDDEN );
    driver.readFile( userC, passC, root + "/dirA707/fileA707", "text/plain", "small1.txt", HttpStatus.SC_OK );
    driver.readFile( userC, passC, root + "/dirA777/fileA777", "text/plain", "small1.txt", HttpStatus.SC_OK );

    //NEGATIVE: Test a bad password.
    if( driver.isUseGateway() ) {
      Response response = given()
          //.log().all()
          .auth().preemptive().basic( userA, "invalid-password" )
          .header("X-XSRF-Header", "jksdhfkhdsf")
          .queryParam( "op", "OPEN" )
          .expect()
          //.log().all()
          .statusCode( HttpStatus.SC_UNAUTHORIZED )
          .when().get( driver.getUrl("WEBHDFS") + "/v1" + root + "/dirA700/fileA700" );
    }
    driver.assertComplete();

    // UPDATE (Negative First)
    driver.updateFile( userC, passC, root + "/dirA700/fileA700", "text/plain", "small2.txt", 307, 403 );
    driver.updateFile( userB, passB, root + "/dirAB700/fileAB700", "text/plain", "small2.txt", 307, 403 );
    driver.updateFile( userB, passB, root + "/dirAB770/fileAB700", "text/plain", "small2.txt", 307, 403 );
    driver.updateFile( userB, passB, root + "/dirAB770/fileAB770", "text/plain", "small2.txt", 307, 403 );
    driver.updateFile( userA, passA, root + "/dirA700/fileA700", "text/plain", "small2.txt", 307, 201 );

    // DELETE (Negative First)
    driver.deleteFile( userC, passC, root + "/dirA700/fileA700", "false", HttpStatus.SC_FORBIDDEN );
    driver.deleteFile( userB, passB, root + "/dirAB700/fileAB700", "false", HttpStatus.SC_FORBIDDEN );
    driver.deleteFile( userB, passB, root + "/dirAB770/fileAB770", "false", HttpStatus.SC_FORBIDDEN );
    driver.deleteFile( userA, passA, root + "/dirA700/fileA700", "false", HttpStatus.SC_OK );

    // Cleanup anything that might have been leftover because the test failed previously.
    driver.deleteFile( userA, passA, root, "true", HttpStatus.SC_OK );
  }

  @Test
  public void testJavaMapReduceViaWebHCat() throws IOException {
    String root = "/tmp/GatewayBasicFuncTest/testJavaMapReduceViaWebHCat";
    String user = "mapred";
    String pass = "mapred-password";
    String group = "mapred";
//    String user = "hcat";
//    String pass = "hcat-password";
//    String group = "hcat";

    // Cleanup anything that might have been leftover because the test failed previously.
    driver.deleteFile( user, pass, root, "true", HttpStatus.SC_OK );

    /* Put the mapreduce code into HDFS. (hadoop-examples.jar)
    curl -X PUT --data-binary @hadoop-examples.jar 'http://192.168.1.163:8888/org.apache.org.apache.hadoop.gateway/cluster/webhdfs/v1/user/hdfs/wordcount/hadoop-examples.jar?user.name=hdfs&op=CREATE'
     */
    driver.createFile( user, pass, null, root+"/hadoop-examples.jar", "777", "application/octet-stream", findHadoopExamplesJar(), 307, 201, 200 );

    /* Put the data file into HDFS (changes.txt)
    curl -X PUT --data-binary @changes.txt 'http://192.168.1.163:8888/org.apache.org.apache.hadoop.gateway/cluster/webhdfs/v1/user/hdfs/wordcount/input/changes.txt?user.name=hdfs&op=CREATE'
     */
    driver.createFile( user, pass, null, root+"/input/changes.txt", "777", "text/plain", "changes.txt", 307, 201, 200 );

    /* Create the output directory
    curl -X PUT 'http://192.168.1.163:8888/org.apache.org.apache.hadoop.gateway/cluster/webhdfs/v1/user/hdfs/wordcount/output?op=MKDIRS&user.name=hdfs'
    */
    driver.createDir( user, pass, null, root+"/output", "777", 200, 200 );

    /* Submit the job
    curl -d user.name=hdfs -d jar=wordcount/hadoop-examples.jar -d class=org.apache.org.apache.hadoop.examples.WordCount -d arg=wordcount/input -d arg=wordcount/output 'http://localhost:8888/org.apache.org.apache.hadoop.gateway/cluster/templeton/v1/mapreduce/jar'
    {"id":"job_201210301335_0059"}
    */
    String job = driver.submitJava(
        user, pass,
        root+"/hadoop-examples.jar", "org.apache.org.apache.hadoop.examples.WordCount",
        root+"/input", root+"/output",
        200 );

    /* Get the job status
    curl 'http://vm:50111/templeton/v1/queue/:jobid?user.name=hdfs'
    */
    driver.queryQueue( user, pass, job );

    // Can't really check for the output here because the job won't be done.
    /* Retrieve results
    curl 'http://192.168.1.163:8888/org.apache.org.apache.hadoop.gateway/cluster/webhdfs/v1/user/hdfs/wordcount/input?op=LISTSTATUS'
    */

    if( CLEANUP_TEST ) {
      // Cleanup anything that might have been leftover because the test failed previously.
      driver.deleteFile( user, pass, root, "true", HttpStatus.SC_OK );
    }
  }

  @Test
  public void testPigViaWebHCat() throws IOException {
    String root = "/tmp/GatewayWebHCatFuncTest/testPigViaWebHCat";
    String user = "mapred";
    String pass = "mapred-password";
    String group = "mapred";

    // Cleanup if previous run failed.
    driver.deleteFile( user, pass, root, "true", 200, 404 );

    // Post the data to HDFS
    driver.createFile( user, pass, null, root + "/passwd.txt", "777", "text/plain", "passwd.txt", 307, 201, 200 );

    // Post the script to HDFS
    driver.createFile( user, pass, null, root+"/script.pig", "777", "text/plain", "script.pig", 307, 201, 200 );

    // Create the output directory
    driver.createDir( user, pass, null, root + "/output", "777", 200, 200 );

    // Submit the job
    driver.submitPig( user, pass, group, root + "/script.pig", "-v", root + "/output", 200 );

    // Check job status (if possible)
    // Check output (if possible)

    // Cleanup
    driver.deleteFile( user, pass, root, "true", 200 );
  }

  @Test
  public void testHiveViaWebHCat() throws IOException {
    String user = "hive";
    String pass = "hive-password";
    String group = "hive";
    String root = "/tmp/GatewayWebHCatFuncTest/testHiveViaWebHCat";

    // Cleanup if previous run failed.
    driver.deleteFile( user, pass, root, "true", 200, 404 );

    // Post the data to HDFS

    // Post the script to HDFS
    driver.createFile( user, pass, null, root + "/script.hive", "777", "text/plain", "script.hive", 307, 201, 200 );

    // Submit the job
    driver.submitHive( user, pass, group, root + "/script.hive", root + "/output", 200 );

    // Check job status (if possible)
    // Check output (if possible)

    // Cleanup
    driver.deleteFile( user, pass, root, "true", 200 );
  }

  @Ignore( "WIP" )
  @Test
  public void testOozieGeneralOperations() {
    String user = "oozie";
    String pass = "oozie-password";
//    driver.oozieVersions( user, pass );
  }

  @Test
  public void testOozieJobSubmission() throws Exception {
    String root = "/tmp/GatewayBasicFuncTest/testOozieJobSubmission";
    String user = "hdfs";
    String pass = "hdfs-password";
    String group = "hdfs";

    // Cleanup anything that might have been leftover because the test failed previously.
    driver.deleteFile( user, pass, root, "true", HttpStatus.SC_OK );

    /* Put the workflow definition into HDFS */
    driver.createFile( user, pass, group, root+"/workflow.xml", "666", "application/octet-stream", "oozie-workflow.xml", 307, 201, 200 );

    /* Put the mapreduce code into HDFS. (hadoop-examples.jar)
    curl -X PUT --data-binary @hadoop-examples.jar 'http://192.168.1.163:8888/org.apache.org.apache.hadoop.gateway/cluster/webhdfs/v1/user/hdfs/wordcount/hadoop-examples.jar?user.name=hdfs&op=CREATE'
     */
    driver.createFile( user, pass, group, root+"/lib/hadoop-examples.jar", "777", "application/octet-stream", findHadoopExamplesJar(), 307, 201, 200 );

    /* Put the data file into HDFS (changes.txt)
    curl -X PUT --data-binary @changes.txt 'http://192.168.1.163:8888/org.apache.org.apache.hadoop.gateway/cluster/webhdfs/v1/user/hdfs/wordcount/input/changes.txt?user.name=hdfs&op=CREATE'
     */
    driver.createFile( user, pass, group, root+"/input/changes.txt", "666", "text/plain", "changes.txt", 307, 201, 200 );

    VelocityEngine velocity = new VelocityEngine();
    velocity.setProperty( RuntimeConstants.RUNTIME_LOG_LOGSYSTEM_CLASS, "org.apache.velocity.runtime.log.NullLogSystem" );
    velocity.setProperty( RuntimeConstants.RESOURCE_LOADER, "classpath" );
    velocity.setProperty( "classpath.resource.loader.class", ClasspathResourceLoader.class.getName() );
    velocity.init();

    VelocityContext context = new VelocityContext();
    context.put( "userName", user );
    context.put( "nameNode", "hdfs://sandbox:8020" );
    context.put( "jobTracker", "sandbox:50300" );
    //context.put( "appPath", "hdfs://sandbox:8020" + root );
    context.put( "appPath", root );
    context.put( "inputDir", root + "/input" );
    context.put( "outputDir", root + "/output" );

    //URL url = TestUtils.getResourceUrl( GatewayBasicFuncTest.class, "oozie-jobs-submit-request.xml" );
    //String name = url.toExternalForm();
    String name = TestUtils.getResourceName( this.getClass(), "oozie-jobs-submit-request.xml" );
    Template template = velocity.getTemplate( name );
    StringWriter sw = new StringWriter();
    template.merge( context, sw );
    String request = sw.toString();
    //System.out.println( "REQUEST=" + request );

    /* Submit the job via Oozie. */
    String id = driver.oozieSubmitJob( user, pass, request, 201 );
    //System.out.println( "ID=" + id );

    String success = "SUCCEEDED";
    String status = "UNKNOWN";
    long delay = 1000 * 1; // 1 second.
    long limit = 1000 * 60; // 60 seconds.
    long start = System.currentTimeMillis();
    while( System.currentTimeMillis() <= start+limit ) {
      status = driver.oozieQueryJobStatus( user, pass, id, 200 );
      //System.out.println( "Status=" + status );
      if( success.equalsIgnoreCase( status ) ) {
        break;
      } else {
        //System.out.println( "Status=" + status );
        Thread.sleep( delay );
      }
    }
    //System.out.println( "Status is " + status + " after " + ((System.currentTimeMillis()-start)/1000) + " seconds." );
    MatcherAssert.assertThat( status, is( success ) );

    if( CLEANUP_TEST ) {
      // Cleanup anything that might have been leftover because the test failed previously.
      driver.deleteFile( user, pass, root, "true", HttpStatus.SC_OK );
    }
  }

  @Test
  public void testBasicHiveJDBCUseCase() throws IOException {
    String root = "/tmp/GatewayHiveJDBCFuncTest/testBasicHiveUseCase";
    String username = "hive";
    String password = "hive-password";
    InetSocketAddress gatewayAddress = driver.gateway.getAddresses()[0];

    // This use case emulates simple JDBC scenario which consists of following steps:
    // -open connection;
    // -configure Hive using 'execute' statements (this also includes execution of 'close operation' requests internally);
    // -execution of create table command;
    // -execution of select from table command;
    // Data insertion is omitted because it causes a lot of additional command during insertion/querying.
    // All binary data was intercepted during real scenario and stored into files as array of bytes.

    // open session
    driver.getMock( "HIVE" )
        .expect()
        .method( "POST" )
        .content( driver.getResourceBytes( "hive/open-session-request.bin" ) )
        .contentType( "application/x-thrift" )
        .respond()
        .characterEncoding( "UTF-8" )
        .status( HttpStatus.SC_OK )
        .content( driver.getResourceBytes( "hive/open-session-result.bin" ) )
        .contentType( "application/x-thrift" );
    Response response = given()
        .auth().preemptive().basic( username, password )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .content( driver.getResourceBytes( "hive/open-session-request.bin" ) )
        .contentType( "application/x-thrift" )
        .expect()
        .statusCode( HttpStatus.SC_OK )
        //.content( is( driver.getResourceBytes( "hive/open-session-result.bin" ) ) )
        .contentType( "application/x-thrift" )
        .when().post( driver.getUrl( "HIVE" ) );
    assertThat( response.body().asByteArray(), is( driver.getResourceBytes( "hive/open-session-result.bin" ) ) );

    driver.assertComplete();

    // execute 'set hive.fetch.output.serde=...' (is called internally be JDBC driver)
    driver.getMock( "HIVE" )
        .expect()
        .method( "POST" )
        .content( driver.getResourceBytes( "hive/execute-set-fetch-output-serde-request.bin" ) )
        .contentType( "application/x-thrift" )
        .respond()
        .characterEncoding( "UTF-8" )
        .status( HttpStatus.SC_OK )
        .content( driver.getResourceBytes( "hive/execute-set-fetch-output-serde-result.bin" ) )
        .contentType( "application/x-thrift" );
    response = given()
        .auth().preemptive().basic( username, password )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .content( driver.getResourceBytes( "hive/execute-set-fetch-output-serde-request.bin" ) )
        .contentType( "application/x-thrift" )
        .expect()
        .statusCode( HttpStatus.SC_OK )
        //.content( is( driver.getResourceBytes( "hive/execute-set-fetch-output-serde-result.bin" ) ) )
        .contentType( "application/x-thrift" )
        .when().post( driver.getUrl( "HIVE" ) );
    assertThat( response.body().asByteArray(), is( driver.getResourceBytes( "hive/execute-set-fetch-output-serde-result.bin" ) ) );
    driver.assertComplete();

    // close operation for execute 'set hive.fetch.output.serde=...'
    driver.getMock( "HIVE" )
        .expect()
        .method( "POST" )
        .content( driver.getResourceBytes( "hive/close-operation-1-request.bin" ) )
        .contentType( "application/x-thrift" )
        .respond()
        .characterEncoding( "UTF-8" )
        .status( HttpStatus.SC_OK )
        .content( driver.getResourceBytes( "hive/close-operation-1-result.bin" ) )
        .contentType( "application/x-thrift" );
    response = given()
        .auth().preemptive().basic( username, password )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .content( driver.getResourceBytes( "hive/close-operation-1-request.bin" ) )
        .contentType( "application/x-thrift" )
        .expect()
        .statusCode( HttpStatus.SC_OK )
        //.content( is( driver.getResourceBytes( "hive/close-operation-1-result.bin" ) ) )
        .contentType( "application/x-thrift" )
        .when().post( driver.getUrl( "HIVE" ) );
    assertThat( response.body().asByteArray(), is( driver.getResourceBytes( "hive/close-operation-1-result.bin" ) ) );
    driver.assertComplete();

    // execute 'set hive.server2.http.path=...' (is called internally be JDBC driver)
    driver.getMock( "HIVE" )
        .expect()
        .method( "POST" )
        .content( driver.getResourceBytes( "hive/execute-set-server2-http-path-request.bin" ) )
        .contentType( "application/x-thrift" )
        .respond()
        .characterEncoding( "UTF-8" )
        .status( HttpStatus.SC_OK )
        .content( driver.getResourceBytes( "hive/execute-set-server2-http-path-result.bin" ) )
        .contentType( "application/x-thrift" );
    response = given()
        .auth().preemptive().basic( username, password )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .content( driver.getResourceBytes( "hive/execute-set-server2-http-path-request.bin" ) )
        .contentType( "application/x-thrift" )
        .expect()
        .statusCode( HttpStatus.SC_OK )
        //.content( is( driver.getResourceBytes( "hive/execute-set-server2-http-path-result.bin" ) ) )
        .contentType( "application/x-thrift" )
        .when().post( driver.getUrl( "HIVE" ) );
    assertThat( response.body().asByteArray(), is( driver.getResourceBytes( "hive/execute-set-server2-http-path-result.bin" ) ) );
    driver.assertComplete();

    // close operation for execute 'set hive.server2.http.path=...'
    driver.getMock( "HIVE" )
        .expect()
        .method( "POST" )
        .content( driver.getResourceBytes( "hive/close-operation-2-request.bin" ) )
        .contentType( "application/x-thrift" )
        .respond()
        .characterEncoding( "UTF-8" )
        .status( HttpStatus.SC_OK )
        .content( driver.getResourceBytes( "hive/close-operation-2-result.bin" ) )
        .contentType( "application/x-thrift" );
    response = given()
        .auth().preemptive().basic( username, password )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .content( driver.getResourceBytes( "hive/close-operation-2-request.bin" ) )
        .contentType( "application/x-thrift" )
        .expect()
        .statusCode( HttpStatus.SC_OK )
        //.content( is( driver.getResourceBytes( "hive/close-operation-2-result.bin" ) ) )
        .contentType( "application/x-thrift" )
        .when().post( driver.getUrl( "HIVE" ) );
    assertThat( response.body().asByteArray(), is( driver.getResourceBytes( "hive/close-operation-2-result.bin" ) ) );
    driver.assertComplete();

    // execute 'set hive.server2.servermode=...' (is called internally be JDBC driver)
    driver.getMock( "HIVE" )
        .expect()
        .method( "POST" )
        .content( driver.getResourceBytes( "hive/execute-set-server2-servermode-request.bin" ) )
        .contentType( "application/x-thrift" )
        .respond()
        .characterEncoding( "UTF-8" )
        .status( HttpStatus.SC_OK )
        .content( driver.getResourceBytes( "hive/execute-set-server2-servermode-result.bin" ) )
        .contentType( "application/x-thrift" );
    response = given()
        .auth().preemptive().basic( username, password )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .content( driver.getResourceBytes( "hive/execute-set-server2-servermode-request.bin" ) )
        .contentType( "application/x-thrift" )
        .expect()
        .statusCode( HttpStatus.SC_OK )
        //.content( is( driver.getResourceBytes( "hive/execute-set-server2-servermode-result.bin" ) ) )
        .contentType( "application/x-thrift" )
        .when().post( driver.getUrl( "HIVE" ) );
    assertThat( response.body().asByteArray(), is( driver.getResourceBytes( "hive/execute-set-server2-servermode-result.bin" ) ) );
    driver.assertComplete();

    // close operation for execute 'set hive.server2.servermode=...'
    driver.getMock( "HIVE" )
        .expect()
        .method( "POST" )
        .content( driver.getResourceBytes( "hive/close-operation-3-request.bin" ) )
        .contentType( "application/x-thrift" )
        .respond()
        .characterEncoding( "UTF-8" )
        .status( HttpStatus.SC_OK )
        .content( driver.getResourceBytes( "hive/close-operation-3-result.bin" ) )
        .contentType( "application/x-thrift" );
    response = given()
        .auth().preemptive().basic( username, password )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .content( driver.getResourceBytes( "hive/close-operation-3-request.bin" ) )
        .contentType( "application/x-thrift" )
        .expect()
        .statusCode( HttpStatus.SC_OK )
        //.content( is( driver.getResourceBytes( "hive/close-operation-3-result.bin" ) ) )
        .contentType( "application/x-thrift" )
        .when().post( driver.getUrl( "HIVE" ) );
    assertThat( response.body().asByteArray(), is( driver.getResourceBytes( "hive/close-operation-3-result.bin" ) ) );
    driver.assertComplete();

    // execute 'set hive.security.authorization.enabled=...'
    driver.getMock( "HIVE" )
        .expect()
        .method( "POST" )
        .content( driver.getResourceBytes( "hive/execute-set-security-authorization-enabled-request.bin" ) )
        .contentType( "application/x-thrift" )
        .respond()
        .characterEncoding( "UTF-8" )
        .status( HttpStatus.SC_OK )
        .content( driver.getResourceBytes( "hive/execute-set-security-authorization-enabled-result.bin" ) )
        .contentType( "application/x-thrift" );
    response = given()
        .auth().preemptive().basic( username, password )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .content( driver.getResourceBytes( "hive/execute-set-security-authorization-enabled-request.bin" ) )
        .contentType( "application/x-thrift" )
        .expect()
        .statusCode( HttpStatus.SC_OK )
        //.content( is( driver.getResourceBytes( "hive/execute-set-security-authorization-enabled-result.bin" ) ) )
        .contentType( "application/x-thrift" )
        .when().post( driver.getUrl( "HIVE" ) );
    assertThat( response.body().asByteArray(), is( driver.getResourceBytes( "hive/execute-set-security-authorization-enabled-result.bin" ) ) );
    driver.assertComplete();

    // close operation for execute 'set hive.security.authorization.enabled=...'
    driver.getMock( "HIVE" )
        .expect()
        .method( "POST" )
        .content( driver.getResourceBytes( "hive/close-operation-4-request.bin" ) )
        .contentType( "application/x-thrift" )
        .respond()
        .characterEncoding( "UTF-8" )
        .status( HttpStatus.SC_OK )
        .content( driver.getResourceBytes( "hive/close-operation-4-result.bin" ) )
        .contentType( "application/x-thrift" );
    response = given()
        .auth().preemptive().basic( username, password )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .content( driver.getResourceBytes( "hive/close-operation-4-request.bin" ) )
        .contentType( "application/x-thrift" )
        .expect()
        .statusCode( HttpStatus.SC_OK )
        //.content( is( driver.getResourceBytes( "hive/close-operation-4-result.bin" ) ) )
        .contentType( "application/x-thrift" )
        .when().post( driver.getUrl( "HIVE" ) );
    assertThat( response.body().asByteArray(), is( driver.getResourceBytes( "hive/close-operation-4-result.bin" ) ) );
    driver.assertComplete();

    // execute 'create table...'
    driver.getMock( "HIVE" )
        .expect()
        .method( "POST" )
        .content( driver.getResourceBytes( "hive/execute-create-table-request.bin" ) )
        .contentType( "application/x-thrift" )
        .respond()
        .characterEncoding( "UTF-8" )
        .status( HttpStatus.SC_OK )
        .content( driver.getResourceBytes( "hive/execute-create-table-result.bin" ) )
        .contentType( "application/x-thrift" );
    response = given()
        .auth().preemptive().basic( username, password )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .content( driver.getResourceBytes( "hive/execute-create-table-request.bin" ) )
        .contentType( "application/x-thrift" )
        .expect()
        .statusCode( HttpStatus.SC_OK )
        //.content( is( driver.getResourceBytes( "hive/execute-create-table-result.bin" ) ) )
        .contentType( "application/x-thrift" )
        .when().post( driver.getUrl( "HIVE" ) );
    assertThat( response.body().asByteArray(), is( driver.getResourceBytes( "hive/execute-create-table-result.bin" ) ) );
    driver.assertComplete();

    // close operation for execute 'create table...'
    driver.getMock( "HIVE" )
        .expect()
        .method( "POST" )
        .content( driver.getResourceBytes( "hive/close-operation-5-request.bin" ) )
        .contentType( "application/x-thrift" )
        .respond()
        .characterEncoding( "UTF-8" )
        .status( HttpStatus.SC_OK )
        .content( driver.getResourceBytes( "hive/close-operation-5-result.bin" ) )
        .contentType( "application/x-thrift" );
    response = given()
        .auth().preemptive().basic( username, password )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .content( driver.getResourceBytes( "hive/close-operation-5-request.bin" ) )
        .contentType( "application/x-thrift" )
        .expect()
        .statusCode( HttpStatus.SC_OK )
        //.content( is( driver.getResourceBytes( "hive/close-operation-5-result.bin" ) ) )
        .contentType( "application/x-thrift" )
        .when().post( driver.getUrl( "HIVE" ) );
    assertThat( response.body().asByteArray(), is( driver.getResourceBytes( "hive/close-operation-5-result.bin" ) ) );
    driver.assertComplete();

    // execute 'select * from...'
    driver.getMock( "HIVE" )
        .expect()
        .method( "POST" )
        .content( driver.getResourceBytes( "hive/execute-select-from-table-request.bin" ) )
        .contentType( "application/x-thrift" )
        .respond()
        .characterEncoding( "UTF-8" )
        .status( HttpStatus.SC_OK )
        .content( driver.getResourceBytes( "hive/execute-select-from-table-result.bin" ) )
        .contentType( "application/x-thrift" );
    response = given()
        .auth().preemptive().basic( username, password )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .content( driver.getResourceBytes( "hive/execute-select-from-table-request.bin" ) )
        .contentType( "application/x-thrift" )
        .expect()
        .statusCode( HttpStatus.SC_OK )
        //.content( is( driver.getResourceBytes( "hive/execute-select-from-table-result.bin" ) ) )
        .contentType( "application/x-thrift" )
        .when().post( driver.getUrl( "HIVE" ) );
    assertThat( response.body().asByteArray(), is( driver.getResourceBytes( "hive/execute-select-from-table-result.bin" ) ) );
    driver.assertComplete();

    // execute 'GetResultSetMetadata' (is called internally be JDBC driver)
    driver.getMock( "HIVE" )
        .expect()
        .method( "POST" )
        .content( driver.getResourceBytes( "hive/get-result-set-metadata-request.bin" ) )
        .contentType( "application/x-thrift" )
        .respond()
        .characterEncoding( "UTF-8" )
        .status( HttpStatus.SC_OK )
        .content( driver.getResourceBytes( "hive/get-result-set-metadata-result.bin" ) )
        .contentType( "application/x-thrift" );
    response = given()
        .auth().preemptive().basic( username, password )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .content( driver.getResourceBytes( "hive/get-result-set-metadata-request.bin" ) )
        .contentType( "application/x-thrift" )
        .expect()
        .statusCode( HttpStatus.SC_OK )
        //.content( is( driver.getResourceBytes( "hive/get-result-set-metadata-result.bin" ) ) )
        .contentType( "application/x-thrift" )
        .when().post( driver.getUrl( "HIVE" ) );
    assertThat( response.body().asByteArray(), is( driver.getResourceBytes( "hive/get-result-set-metadata-result.bin" ) ) );
    driver.assertComplete();

    // execute 'FetchResults' (is called internally be JDBC driver)
    driver.getMock( "HIVE" )
        .expect()
        .method( "POST" )
        .content( driver.getResourceBytes( "hive/fetch-results-request.bin" ) )
        .contentType( "application/x-thrift" )
        .respond()
        .characterEncoding( "UTF-8" )
        .status( HttpStatus.SC_OK )
        .content( driver.getResourceBytes( "hive/fetch-results-result.bin" ) )
        .contentType( "application/x-thrift" );
    response = given()
        .auth().preemptive().basic( username, password )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .content( driver.getResourceBytes( "hive/fetch-results-request.bin" ) )
        .contentType( "application/x-thrift" )
        .expect()
        .statusCode( HttpStatus.SC_OK )
        //.content( is( driver.getResourceBytes( "hive/fetch-results-result.bin" ) ) )
        .contentType( "application/x-thrift" )
        .when().post( driver.getUrl( "HIVE" ) );
    assertThat( response.body().asByteArray(), is( driver.getResourceBytes( "hive/fetch-results-result.bin" ) ) );
    driver.assertComplete();

    // close operation for execute 'select * from...'
    driver.getMock( "HIVE" )
        .expect()
        .method( "POST" )
        .content( driver.getResourceBytes( "hive/close-operation-6-request.bin" ) )
        .contentType( "application/x-thrift" )
        .respond()
        .characterEncoding( "UTF-8" )
        .status( HttpStatus.SC_OK )
        .content( driver.getResourceBytes( "hive/close-operation-6-result.bin" ) )
        .contentType( "application/x-thrift" );
    response = given()
        .auth().preemptive().basic( username, password )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .content( driver.getResourceBytes( "hive/close-operation-6-request.bin" ) )
        .contentType( "application/x-thrift" )
        .expect()
        .statusCode( HttpStatus.SC_OK )
        //.content( is( driver.getResourceBytes( "hive/close-operation-6-result.bin" ) ) )
        .contentType( "application/x-thrift" )
        .when().post( driver.getUrl( "HIVE" ) );
    assertThat( response.body().asByteArray(), is( driver.getResourceBytes( "hive/close-operation-6-result.bin" ) ) );
    driver.assertComplete();

    // close session
    driver.getMock( "HIVE" )
        .expect()
        .method( "POST" )
        .content( driver.getResourceBytes( "hive/close-session-request.bin" ) )
        .contentType( "application/x-thrift" )
        .respond()
        .characterEncoding( "UTF-8" )
        .status( HttpStatus.SC_OK )
        .content( driver.getResourceBytes( "hive/close-session-result.bin" ) )
        .contentType( "application/x-thrift" );
    response = given()
        .auth().preemptive().basic( username, password )
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .content( driver.getResourceBytes( "hive/close-session-request.bin" ) )
        .contentType( "application/x-thrift" )
        .expect()
        .statusCode( HttpStatus.SC_OK )
        //.content( is( driver.getResourceBytes( "hive/close-session-result.bin" ) ) )
        .contentType( "application/x-thrift" )
        .when().post( driver.getUrl( "HIVE" ) );
    assertThat( response.body().asByteArray(), is( driver.getResourceBytes( "hive/close-session-result.bin" ) ) );
    driver.assertComplete();
  }

  @Test
  public void testHBaseGetTableList() throws IOException {
    String username = "hbase";
    String password = "hbase-password";
    String resourceName = "hbase/table-list";
    
    driver.getMock( "WEBHBASE" )
    .expect()
    .method( "GET" )
    .pathInfo( "/" )
    .header( "Accept", ContentType.XML.toString() )
    .respond()
    .status( HttpStatus.SC_OK )
    .content( driver.getResourceBytes( resourceName + ".xml" ) )
    .contentType( ContentType.XML.toString() );
    
    Response response = given()
    .auth().preemptive().basic( username, password )
    .header("X-XSRF-Header", "jksdhfkhdsf")
    .header( "Accept", ContentType.XML.toString() )
    .expect()
    .statusCode( HttpStatus.SC_OK )
    .contentType( ContentType.XML )
    .when().get( driver.getUrl( "WEBHBASE" ) );
    
    MatcherAssert
        .assertThat(
            the( response.getBody().asString() ),
            isEquivalentTo( the( driver.getResourceString( resourceName + ".xml", UTF8 ) ) ) );
    driver.assertComplete();
    
    driver.getMock( "WEBHBASE" )
    .expect()
    .method( "GET" )
    .pathInfo( "/" )
    .header( "Accept", ContentType.JSON.toString() )
    .respond()
    .status( HttpStatus.SC_OK )
    .content( driver.getResourceBytes( resourceName + ".json" ) )
    .contentType( ContentType.JSON.toString() );
    
    response = given()
    .auth().preemptive().basic( username, password )
    .header("X-XSRF-Header", "jksdhfkhdsf")
    .header( "Accept", ContentType.JSON.toString() )
    .expect()
    .statusCode( HttpStatus.SC_OK )
    .contentType( ContentType.JSON )
    .when().get( driver.getUrl( "WEBHBASE" ) );
    
    MatcherAssert
    .assertThat( response.getBody().asString(), sameJSONAs( driver.getResourceString( resourceName + ".json", UTF8 ) ) );
    driver.assertComplete();
    
    driver.getMock( "WEBHBASE" )
    .expect()
    .method( "GET" )
    .pathInfo( "/" )
    .header( "Accept", "application/x-protobuf" )
    .respond()
    .status( HttpStatus.SC_OK )
    .content( driver.getResourceString( resourceName + ".protobuf", UTF8 ), UTF8 )
    .contentType( "application/x-protobuf" );
    
    given()
    .auth().preemptive().basic( username, password )
    .header("X-XSRF-Header", "jksdhfkhdsf")
    .header( "Accept", "application/x-protobuf" )
    .expect()
    .statusCode( HttpStatus.SC_OK )
    .contentType( "application/x-protobuf" )
    .content( is( driver.getResourceString( resourceName + ".protobuf", UTF8 ) ) )
    .when().get( driver.getUrl( "WEBHBASE" ) );
    driver.assertComplete();
  }

  @Test
  public void testHBaseCreateTableAndVerifySchema() throws IOException {
    String username = "hbase";
    String password = "hbase-password";
    String resourceName = "hbase/table-schema";
    String path = "/table/schema";

    driver.getMock( "WEBHBASE" )
    .expect()
    .method( "PUT" )
    .pathInfo( path )
    .respond()
    .status( HttpStatus.SC_CREATED )
    .content( driver.getResourceBytes( resourceName + ".xml" ) )
    .contentType( ContentType.XML.toString() )
    .header( "Location", driver.getRealUrl( "WEBHBASE" ) + path  );

    given()
    .auth().preemptive().basic( username, password )
    .header("X-XSRF-Header", "jksdhfkhdsf")
    .expect()
    .statusCode( HttpStatus.SC_CREATED )
    .contentType( ContentType.XML )
    .header( "Location", startsWith( driver.getUrl( "WEBHBASE" ) + path ) )
    .when().put( driver.getUrl( "WEBHBASE" ) + path );
    driver.assertComplete();

    driver.getMock( "WEBHBASE" )
    .expect()
    .method( "PUT" )
    .pathInfo( path )
    .respond()
    .status( HttpStatus.SC_CREATED )
    .content( driver.getResourceBytes( resourceName + ".json" ) )
    .contentType( ContentType.JSON.toString() )
    .header( "Location", driver.getRealUrl( "WEBHBASE" ) + path );
    
    given()
    .auth().preemptive().basic( username, password )
    .header("X-XSRF-Header", "jksdhfkhdsf")
    .expect()
    .statusCode( HttpStatus.SC_CREATED )
    .contentType( ContentType.JSON )
    .header( "Location", startsWith( driver.getUrl( "WEBHBASE" ) + path ) )
    .when().put( driver.getUrl( "WEBHBASE" ) + path );
    driver.assertComplete();

    driver.getMock( "WEBHBASE" )
    .expect()
    .method( "PUT" )
    .pathInfo( path )
    .respond()
    .status( HttpStatus.SC_CREATED )
    .content( driver.getResourceBytes( resourceName + ".protobuf" ) )
    .contentType( "application/x-protobuf" )
    .header( "Location", driver.getRealUrl( "WEBHBASE" ) + path );

    given()
    .auth().preemptive().basic( username, password )
    .header( "X-XSRF-Header", "jksdhfkhdsf" )
    .expect()
    .statusCode( HttpStatus.SC_CREATED )
    .contentType( "application/x-protobuf" )
    .header( "Location", startsWith( driver.getUrl( "WEBHBASE" ) + path ) )
    .when().put( driver.getUrl( "WEBHBASE" ) + path );
    driver.assertComplete();

  }

  @Test
  public void testHBaseGetTableSchema() throws IOException {
    String username = "hbase";
    String password = "hbase-password";
    String resourceName = "hbase/table-metadata";
    String path = "/table/schema";
    
    driver.getMock( "WEBHBASE" )
    .expect()
    .method( "GET" )
    .pathInfo( path )
    .header( "Accept", ContentType.XML.toString() )
    .respond()
    .status( HttpStatus.SC_OK )
    .content( driver.getResourceBytes( resourceName + ".xml" ) )
    .contentType( ContentType.XML.toString() );

    Response response = given()
    .auth().preemptive().basic( username, password )
    .header("X-XSRF-Header", "jksdhfkhdsf")
    .header( "Accept", ContentType.XML.toString() )
    .expect()
    .statusCode( HttpStatus.SC_OK )
    .contentType( ContentType.XML )
    .when().get( driver.getUrl( "WEBHBASE" ) + path );

    MatcherAssert
        .assertThat(
            the( response.getBody().asString() ),
            isEquivalentTo( the( driver.getResourceString( resourceName + ".xml", UTF8 ) ) ) );
    driver.assertComplete();
    
    driver.getMock( "WEBHBASE" )
    .expect()
    .method( "GET" )
    .pathInfo( path )
    .header( "Accept", ContentType.JSON.toString() )
    .respond()
    .status( HttpStatus.SC_OK )
    .content( driver.getResourceBytes( resourceName + ".json" ) )
    .contentType( ContentType.JSON.toString() );

    response = given()
    .auth().preemptive().basic( username, password )
    .header("X-XSRF-Header", "jksdhfkhdsf")
    .header( "Accept", ContentType.JSON.toString() )
    .expect()
    .statusCode( HttpStatus.SC_OK )
    .contentType( ContentType.JSON )
    .when().get( driver.getUrl( "WEBHBASE" ) + path );
    
    MatcherAssert
    .assertThat( response.getBody().asString(), sameJSONAs( driver.getResourceString( resourceName + ".json", UTF8 ) ) );
    driver.assertComplete();
    
    driver.getMock( "WEBHBASE" )
    .expect()
    .method( "GET" )
    .pathInfo( path )
    .header( "Accept", "application/x-protobuf" )
    .respond()
    .status( HttpStatus.SC_OK )
    .content( driver.getResourceBytes( resourceName + ".protobuf" ) )
    .contentType( "application/x-protobuf" );

    response = given()
    .auth().preemptive().basic( username, password )
    .header("X-XSRF-Header", "jksdhfkhdsf")
    .header( "Accept", "application/x-protobuf" )
    .expect()
    .statusCode( HttpStatus.SC_OK )
    //.content( is( driver.getResourceBytes( resourceName + ".protobuf" ) ) )
    .contentType( "application/x-protobuf" )
    .when().get( driver.getUrl( "WEBHBASE" ) + path );
    // RestAssured seems to be screwing up the binary comparison so do it explicitly.
    assertThat( driver.getResourceBytes( resourceName + ".protobuf" ), is( response.body().asByteArray() ) );
    driver.assertComplete();
  }

  @Test
  public void testHBaseInsertDataIntoTable() throws IOException {
    String username = "hbase";
    String password = "hbase-password";
    
    String resourceName = "hbase/table-data";
    String singleRowPath = "/table/testrow";
    String multipleRowPath = "/table/false-row-key";
    
    //PUT request
    
    driver.getMock( "WEBHBASE" )
    .expect()
    .method( "PUT" )
    .pathInfo( multipleRowPath )
    //.header( "Content-Type", ContentType.XML.toString() )
    .content( driver.getResourceBytes( resourceName + ".xml" ) )
    .contentType( ContentType.XML.toString() )
    .respond()
    .status( HttpStatus.SC_OK );

    given()
    .auth().preemptive().basic( username, password )
    .header("X-XSRF-Header", "jksdhfkhdsf")
    //.header( "Content-Type", ContentType.XML.toString() )
    .content( driver.getResourceBytes( resourceName + ".xml" ) )
    .contentType( ContentType.XML.toString() )
    .expect()
    .statusCode( HttpStatus.SC_OK )
    .when().put( driver.getUrl( "WEBHBASE" ) + multipleRowPath );
    driver.assertComplete();
    
    driver.getMock( "WEBHBASE" )
    .expect()
    .method( "PUT" )
    .pathInfo( singleRowPath )
    //.header( "Content-Type", ContentType.JSON.toString() )
    .contentType( ContentType.JSON.toString() )
    .respond()
    .status( HttpStatus.SC_OK );

    given()
    .auth().preemptive().basic( username, password )
    .header("X-XSRF-Header", "jksdhfkhdsf")
    //.header( "Content-Type", ContentType.JSON.toString() )
    .content( driver.getResourceBytes( resourceName + ".json" ) )
    .contentType( ContentType.JSON.toString() )
    .expect()
    .statusCode( HttpStatus.SC_OK )
    .when().put( driver.getUrl( "WEBHBASE" ) + singleRowPath );
    driver.assertComplete();
 
    driver.getMock( "WEBHBASE" )
    .expect()
    .method( "PUT" )
    .pathInfo( multipleRowPath )
    //.header( "Content-Type", "application/x-protobuf" )
    .contentType( "application/x-protobuf" )
    .content( driver.getResourceBytes( resourceName + ".protobuf" ) )
    .respond()
    .status( HttpStatus.SC_OK );

    given()
    .auth().preemptive().basic( username, password )
    .header("X-XSRF-Header", "jksdhfkhdsf")
    //.header( "Content-Type", "application/x-protobuf" )
    .content( driver.getResourceBytes( resourceName + ".protobuf" ) )
    .contentType( "application/x-protobuf" )
    .expect()
    .statusCode( HttpStatus.SC_OK )
    .when().put( driver.getUrl( "WEBHBASE" ) + multipleRowPath );
    driver.assertComplete();
    
    //POST request
    
    driver.getMock( "WEBHBASE" )
    .expect()
    .method( "POST" )
    .pathInfo( multipleRowPath )
    //.header( "Content-Type", ContentType.XML.toString() )
    .content( driver.getResourceBytes( resourceName + ".xml" ) )
    .contentType( ContentType.XML.toString() )
    .respond()
    .status( HttpStatus.SC_OK );

    given()
      .auth().preemptive().basic( username, password )
      .header("X-XSRF-Header", "jksdhfkhdsf")
      //.header( "Content-Type", ContentType.XML.toString() )
      .content( driver.getResourceBytes( resourceName + ".xml" ) )
      .contentType( ContentType.XML.toString() )
      .expect()
      .statusCode( HttpStatus.SC_OK )
      .when().post( driver.getUrl( "WEBHBASE" ) + multipleRowPath );
    driver.assertComplete();

    driver.getMock( "WEBHBASE" )
    .expect()
    .method( "POST" )
    .pathInfo( singleRowPath )
    //.header( "Content-Type", ContentType.JSON.toString() )
    .contentType( ContentType.JSON.toString() )
    .respond()
    .status( HttpStatus.SC_OK );

    given()
    .auth().preemptive().basic( username, password )
    .header("X-XSRF-Header", "jksdhfkhdsf")
    //.header( "Content-Type", ContentType.JSON.toString() )
    .content( driver.getResourceBytes( resourceName + ".json" ) )
    .contentType( ContentType.JSON.toString() )
    .expect()
    .statusCode( HttpStatus.SC_OK )
    .when().post( driver.getUrl( "WEBHBASE" ) + singleRowPath );
    driver.assertComplete();
    
    driver.getMock( "WEBHBASE" )
    .expect()
    .method( "POST" )
    .pathInfo( multipleRowPath )
    //.header( "Content-Type", "application/x-protobuf" )
    .content( driver.getResourceBytes( resourceName + ".protobuf" ) )
    .contentType( "application/x-protobuf" )
    .respond()
    .status( HttpStatus.SC_OK );

    given()
    .auth().preemptive().basic( username, password )
    .header("X-XSRF-Header", "jksdhfkhdsf")
    //.header( "Content-Type", "application/x-protobuf" )
    .content( driver.getResourceBytes( resourceName + ".protobuf" ) )
    .contentType( "application/x-protobuf" )
    .expect()
    .statusCode( HttpStatus.SC_OK )
    .when().post( driver.getUrl( "WEBHBASE" ) + multipleRowPath );
    driver.assertComplete();
  }

  @Test
  public void testHBaseDeleteDataFromTable() {
    String username = "hbase";
    String password = "hbase-password";
    
    String tableId = "table";
    String rowId = "row";
    String familyId = "family";
    String columnId = "column";
    
    driver.getMock( "WEBHBASE" )
    .expect()
    .from( "testHBaseDeleteDataFromTable-1" )
    .method( "DELETE" )
    .pathInfo( "/" + tableId + "/" + rowId )
    .respond()
    .status( HttpStatus.SC_OK );

    given()
    .auth().preemptive().basic( username, password )
    .header( "X-XSRF-Header", "jksdhfkhdsf" )
    .expect()
    .statusCode( HttpStatus.SC_OK )
    .when().delete( driver.getUrl( "WEBHBASE" ) + "/" + tableId + "/" + rowId );
    driver.assertComplete();
    
    driver.getMock( "WEBHBASE" )
    .expect()
    .from( "testHBaseDeleteDataFromTable-2" )
    .method( "DELETE" )
    .pathInfo( "/" + tableId + "/" + rowId + "/" + familyId )
    .respond()
    .status( HttpStatus.SC_OK );

    given()
    .auth().preemptive().basic( username, password )
    .header( "X-XSRF-Header", "jksdhfkhdsf" )
    .expect()
    .statusCode( HttpStatus.SC_OK )
    .when().delete( driver.getUrl( "WEBHBASE" ) + "/" + tableId + "/" + rowId + "/" + familyId );
    driver.assertComplete();

    driver.getMock( "WEBHBASE" )
    .expect()
    .from( "testHBaseDeleteDataFromTable-3" )
    .method( "DELETE" )
    .pathInfo( "/" + tableId + "/" + rowId + "/" + familyId + ":" + columnId )
    .respond()
    .status( HttpStatus.SC_OK );

    given()
    .auth().preemptive().basic( username, password )
    .header( "X-XSRF-Header", "jksdhfkhdsf" )
    .expect()
    .statusCode( HttpStatus.SC_OK )
    .when().delete( driver.getUrl( "WEBHBASE" ) + "/" + tableId + "/" + rowId + "/" + familyId + ":" + columnId );
    driver.assertComplete();

  }

  @Test
  public void testHBaseQueryTableData() throws IOException {
    String username = "hbase";
    String password = "hbase-password";
    
    String resourceName = "hbase/table-data";
    
    String allRowsPath = "/table/*";
    String rowsStartsWithPath = "/table/row*";
    String rowsWithKeyPath = "/table/row";
    String rowsWithKeyAndColumnPath = "/table/row/family:col";
    
    driver.getMock( "WEBHBASE" )
    .expect()
    .method( "GET" )
    .pathInfo( allRowsPath )
    .header( "Accept", ContentType.XML.toString() )
    .respond()
    .status( HttpStatus.SC_OK )
    .content( driver.getResourceBytes( resourceName + ".xml" ) )
    .contentType( ContentType.XML.toString() );

    Response response = given()
    .auth().preemptive().basic( username, password )
    .header("X-XSRF-Header", "jksdhfkhdsf")
    .header( "Accept", ContentType.XML.toString() )
    .expect()
    .statusCode( HttpStatus.SC_OK )
    .contentType( ContentType.XML )
    .when().get( driver.getUrl( "WEBHBASE" ) + allRowsPath );
    
    MatcherAssert
    .assertThat(
        the( response.getBody().asString() ),
        isEquivalentTo( the( driver.getResourceString( resourceName + ".xml", UTF8 ) ) ) );
    driver.assertComplete();
    
    driver.getMock( "WEBHBASE" )
    .expect()
    .method( "GET" )
    .pathInfo( rowsStartsWithPath )
    .header( "Accept", ContentType.XML.toString() )
    .respond()
    .status( HttpStatus.SC_OK )
    .content( driver.getResourceBytes( resourceName + ".xml" ) )
    .contentType( ContentType.XML.toString() );

    response = given()
    .auth().preemptive().basic( username, password )
    .header("X-XSRF-Header", "jksdhfkhdsf")
    .header( "Accept", ContentType.XML.toString() )
    .expect()
    .statusCode( HttpStatus.SC_OK )
    .contentType( ContentType.XML )
    .when().get( driver.getUrl( "WEBHBASE" ) + rowsStartsWithPath );
    
    MatcherAssert
    .assertThat(
        the( response.getBody().asString() ),
        isEquivalentTo( the( driver.getResourceString( resourceName + ".xml", UTF8 ) ) ) );
    driver.assertComplete();
    
    driver.getMock( "WEBHBASE" )
    .expect()
    .method( "GET" )
    .pathInfo( rowsWithKeyPath )
    .header( "Accept", ContentType.JSON.toString() )
    .respond()
    .status( HttpStatus.SC_OK )
    .content( driver.getResourceBytes( resourceName + ".json" ) )
    .contentType( ContentType.JSON.toString() );

    response = given()
    .auth().preemptive().basic( username, password )
    .header("X-XSRF-Header", "jksdhfkhdsf")
    .header( "Accept", ContentType.JSON.toString() )
    .expect()
    .statusCode( HttpStatus.SC_OK )
    .contentType( ContentType.JSON )
    .when().get( driver.getUrl( "WEBHBASE" ) + rowsWithKeyPath );
    
    MatcherAssert
    .assertThat( response.getBody().asString(), sameJSONAs( driver.getResourceString( resourceName + ".json", UTF8 ) ) );
    driver.assertComplete();
    
    driver.getMock( "WEBHBASE" )
    .expect()
    .method( "GET" )
    .pathInfo( rowsWithKeyAndColumnPath )
    .header( "Accept", ContentType.JSON.toString() )
    .respond()
    .status( HttpStatus.SC_OK )
    .content( driver.getResourceBytes( resourceName + ".json" ) )
    .contentType( ContentType.JSON.toString() );

    response = given()
    .auth().preemptive().basic( username, password )
    .header("X-XSRF-Header", "jksdhfkhdsf")
    .header( "Accept", ContentType.JSON.toString() )
    .expect()
    .statusCode( HttpStatus.SC_OK )
    .contentType( ContentType.JSON )
    .when().get( driver.getUrl( "WEBHBASE" ) + rowsWithKeyAndColumnPath );
    
    MatcherAssert
    .assertThat( response.getBody().asString(), sameJSONAs( driver.getResourceString( resourceName + ".json", UTF8 ) ) );
    driver.assertComplete();
  }

  @Test
  public void testHBaseUseScanner() throws IOException {
    String username = "hbase";
    String password = "hbase-password";
    
    String scannerDefinitionResourceName = "hbase/scanner-definition";
    String tableDataResourceName = "hbase/table-data";
    String scannerPath = "/table/scanner";
    String scannerId = "13705290446328cff5ed";
    
    //Create scanner for table using PUT and POST requests
    driver.getMock( "WEBHBASE" )
    .expect()
    .method( "PUT" )
    .pathInfo( scannerPath )
    .header( "Content-Type", ContentType.XML.toString() )
    .respond()
    .status( HttpStatus.SC_CREATED );

    given()
    .auth().preemptive().basic( username, password )
    .header("X-XSRF-Header", "jksdhfkhdsf")
    .header( "Content-Type", ContentType.XML.toString() )
    .content( driver.getResourceBytes( scannerDefinitionResourceName + ".xml" ) )
    .expect()
    //TODO: Add "Location" header check  when issue with incorrect outbound rewrites will be resolved
    //.header( "Location", startsWith( driver.getUrl( "WEBHBASE" ) + createScannerPath ) )
    .statusCode( HttpStatus.SC_CREATED )
    .when().put( driver.getUrl( "WEBHBASE" ) + scannerPath );
    driver.assertComplete();
    
    //Get the values of the next cells found by the scanner 
    driver.getMock( "WEBHBASE" )
    .expect()
    .method( "GET" )
    .pathInfo( scannerPath + "/" + scannerId )
    .header( "Accept", ContentType.XML.toString() )
    .respond()
    .status( HttpStatus.SC_OK )
    .content( driver.getResourceBytes( tableDataResourceName + ".xml" ) )
    .contentType( ContentType.XML.toString() );

    Response response = given()
    .auth().preemptive().basic( username, password )
    .header("X-XSRF-Header", "jksdhfkhdsf")
    .header( "Accept", ContentType.XML.toString() )
    .expect()
    .statusCode( HttpStatus.SC_OK )
    .contentType( ContentType.XML )
    .when().get( driver.getUrl( "WEBHBASE" ) + scannerPath + "/" + scannerId );
    
    MatcherAssert
    .assertThat(
        the( response.getBody().asString() ),
        isEquivalentTo( the( driver.getResourceString( tableDataResourceName + ".xml", UTF8 ) ) ) );
    driver.assertComplete();
    
    //Delete scanner
    driver.getMock( "WEBHBASE" )
    .expect()
    .from( "testHBaseUseScanner" )
    .method( "DELETE" )
    .pathInfo( scannerPath + "/" + scannerId )
    .respond()
    .status( HttpStatus.SC_OK );

    given()
    .auth().preemptive().basic( username, password )
    .header("X-XSRF-Header", "jksdhfkhdsf")
    .expect()
    .statusCode( HttpStatus.SC_OK )
    .when().delete( driver.getUrl( "WEBHBASE" ) + scannerPath + "/" + scannerId );
    driver.assertComplete();
  }

  @Test
  public void testCrossSiteRequestForgeryPreventionPUT() throws IOException {
    String root = "/tmp/GatewayWebHdfsFuncTest/testCrossSiteRequestForgeryPrevention";
    String username = "hdfs";
    String password = "hdfs-password";

    given()
//        .log().all()
        .auth().preemptive().basic( username, password )
//        .header("X-XSRF-Header", "jksdhfkhdsf")
        .queryParam( "op", "MKDIRS" )
        .expect()
//            .log().all()
        .statusCode( HttpStatus.SC_BAD_REQUEST )
        .when().put( driver.getUrl( "WEBHDFS" ) + "/v1" + root + "/dir" );
    driver.assertComplete();
  }

  @Test
  public void testCrossSiteRequestForgeryPreventionGET() throws IOException {
    String root = "/tmp/GatewayWebHdfsFuncTest/testCrossSiteRequestForgeryPrevention";
    String username = "hdfs";
    String password = "hdfs-password";

    driver.getMock( "WEBHDFS" )
        .expect()
        .method( "GET" )
        .pathInfo( "/v1" + root + "/dir" )
        .queryParam( "op", "LISTSTATUS" )
        .queryParam( "user.name", username )
        .respond()
        .status( HttpStatus.SC_OK );
    given()
//        .log().all()
        .auth().preemptive().basic( username, password )
//        .header("X-XSRF-Header", "jksdhfkhdsf")
        .queryParam( "op", "LISTSTATUS" )
        .expect()
//            .log().all()
        .statusCode( HttpStatus.SC_OK )
        .when().get( driver.getUrl( "WEBHDFS" ) + "/v1" + root + "/dir" );
//    driver.reset();
    driver.assertComplete();
  }

  @Test
  public void testYarnRmGetClusterInfo() throws Exception {
    getYarnRmResource( "/v1/cluster/", ContentType.JSON, "yarn/cluster-info" );
    getYarnRmResource( "/v1/cluster/", ContentType.XML, "yarn/cluster-info" );
    getYarnRmResource( "/v1/cluster/info/", ContentType.JSON, "yarn/cluster-info" );
    getYarnRmResource( "/v1/cluster/info/", ContentType.XML, "yarn/cluster-info" );
  }

  @Test
  public void testYarnRmGetClusterMetrics() throws Exception {
    getYarnRmResource( "/v1/cluster/metrics/", ContentType.JSON, "yarn/cluster-metrics" );
    getYarnRmResource( "/v1/cluster/metrics/", ContentType.XML, "yarn/cluster-metrics" );
  }

  @Test
  public void testYarnRnGetScheduler() throws Exception {
    getYarnRmResource( "/v1/cluster/scheduler/", ContentType.JSON, "yarn/scheduler" );
    getYarnRmResource( "/v1/cluster/scheduler/", ContentType.XML, "yarn/scheduler" );
  }

  @Test
  public void getYarnRmAppstatistics() throws Exception {
    getYarnRmResource( "/v1/cluster/appstatistics/", ContentType.JSON, "yarn/appstatistics" );
    getYarnRmResource( "/v1/cluster/appstatistics/", ContentType.XML, "yarn/appstatistics" );
  }

  @Test
  public void testYarnRmGetApplications() throws Exception {
    getYarnRmApps( ContentType.XML, null );
    getYarnRmApps( ContentType.JSON, null );

    Map<String, String> params = new HashMap<String, String>();
    params.put( "states", "FINISHED" );
    params.put( "finalStatus", "SUCCEEDED" );
    params.put( "user", "test" );
    params.put( "queue", "queueName" );
    params.put( "limit", "100" );
    params.put( "startedTimeBegin", "1399903578539" );
    params.put( "startedTimeEnd", "1399903578539" );
    params.put( "finishedTimeBegin", "1399904819572" );
    params.put( "finishedTimeEnd", "1399904819572" );
    params.put( "applicationTypes", "MAPREDUCE" );
    params.put( "applicationTags", "a" );

    getYarnRmApps( ContentType.XML, params );
    getYarnRmApps( ContentType.JSON, params );
  }

  private void getYarnRmApps( ContentType contentType, Map<String,String> params ) throws Exception {
    String username = "hdfs";
    String password = "hdfs-password";
    String path = "/v1/cluster/apps/";
    String resource = "/yarn/apps";
    String gatewayPath = driver.getUrl( "RESOURCEMANAGER" ) + path;
    String gatewayPathQuery = driver.isUseGateway() ? "" : "?user.name=" + username;
    InetSocketAddress gatewayAddress = driver.gateway.getAddresses()[0];
    String gatewayHostName = gatewayAddress.getHostName();
    String gatewayAddrName = InetAddress.getByName( gatewayHostName ).getHostAddress();

    switch( contentType ) {
    case JSON:
      resource += ".json";
      break;
    case XML:
      resource += ".xml";
      break;
    default:
      break;
    }

    MockRequestMatcher mockRequestMatcher = driver.getMock( "RESOURCEMANAGER" ).expect().method( "GET" )
        .pathInfo( path ).queryParam( "user.name", username );

    if ( params != null ) {
      for (Entry<String, String> param : params.entrySet()) {
        mockRequestMatcher.queryParam( param.getKey(), param.getValue() );
        if (gatewayPathQuery.isEmpty()) {
          gatewayPathQuery += "?";
        } else {
          gatewayPathQuery += "&";
        }
        gatewayPathQuery += param.getKey() + "=" + param.getValue();
      }
    }


    mockRequestMatcher.respond()
        .status( HttpStatus.SC_OK )
        .content( driver.getResourceBytes( resource ) )
        .contentType( contentType.toString() );

    given()
//         .log().all()
        .auth()
        .preemptive()
        .basic( username, password )
        .header( "X-XSRF-Header", "jksdhfkhdsf" )
        .expect()
//         .log().all()
        .statusCode( HttpStatus.SC_OK )
        .contentType( contentType )
        .content( "apps.app[0].trackingUrl", isEmptyString() )
        .content( "apps.app[1].trackingUrl",
            anyOf(
                startsWith( "http://" + gatewayHostName + ":" + gatewayAddress.getPort() + "/" ),
                startsWith( "http://" + gatewayAddrName + ":" + gatewayAddress.getPort() + "/" ) ) )
        .content( "apps.app[2].trackingUrl", isEmptyString() )
        .content( "apps.app[0].amContainerLogs", isEmptyString() )
        .content( "apps.app[1].amContainerLogs", isEmptyString() )
        .content( "apps.app[0].amHostHttpAddress", isEmptyString() )
        .content( "apps.app[1].amHostHttpAddress", isEmptyString() )
        .content( "apps.app[2].id", is( "application_1399541193872_0009" ) )
        .when()
        .get( gatewayPath + gatewayPathQuery );

    driver.assertComplete();
  }

  @Test
  public void testYarnApplicationLifecycle() throws Exception {
    String username = "hdfs";
    String password = "hdfs-password";
    String path = "/v1/cluster/apps/new-application";
    String resource = "yarn/new-application.json";

    driver.getMock("RESOURCEMANAGER")
        .expect()
        .method("POST")
        .respond()
        .status(HttpStatus.SC_OK)
        .content(driver.getResourceBytes(resource))
        .contentType("application/json");
    Response response = given()
        .auth().preemptive().basic(username, password)
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .expect()
        .statusCode(HttpStatus.SC_OK)
        .contentType("application/json")
        .when().post(driver.getUrl("RESOURCEMANAGER") + path + (driver.isUseGateway() ? "" : "?user.name=" + username));
    assertThat(response.getBody().asString(), Matchers.containsString("application-id"));

    path = "/v1/cluster/apps";
    resource = "yarn/application-submit-request.json";

    driver.getMock("RESOURCEMANAGER")
        .expect()
        .method("POST")
        .content(driver.getResourceBytes(resource))
        .contentType("application/json")
        .respond()
        .status(HttpStatus.SC_OK)
        .contentType("application/json");
    given()
        .auth().preemptive().basic(username, password)
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .content(driver.getResourceBytes(resource))
        .contentType("application/json")
        .expect()
        .statusCode(HttpStatus.SC_OK)
        .contentType("application/json")
        .when().post(driver.getUrl("RESOURCEMANAGER") + path + (driver.isUseGateway() ? "" : "?user.name=" + username));
    driver.assertComplete();

    path = "/v1/cluster/apps/application_1405356982244_0031/state";
    resource = "yarn/application-killing.json";
    driver.getMock("RESOURCEMANAGER")
        .expect()
        .method("PUT")
        .content(driver.getResourceBytes(resource))
        .contentType("application/json")
        .respond()
        .status(HttpStatus.SC_OK)
        .content(driver.getResourceBytes(resource))
        .contentType("application/json");
    response = given()
        .auth().preemptive().basic(username, password)
        .header("X-XSRF-Header", "jksdhfkhdsf")
        .content(driver.getResourceBytes(resource))
        .contentType("application/json")
        .expect()
        .statusCode(HttpStatus.SC_OK)
        .contentType("application/json")
        .when().put(driver.getUrl("RESOURCEMANAGER") + path + (driver.isUseGateway() ? "" : "?user.name=" + username));
    assertThat(response.getBody().asString(), Matchers.is("{\"state\":\"KILLING\"}"));
  }

  @Test
  public void testYarnRmApplication() throws Exception {
    getYarnRmApp( ContentType.JSON, true );
    getYarnRmApp( ContentType.XML, true );
    getYarnRmApp( ContentType.JSON, false );
    getYarnRmApp( ContentType.XML, false );
  }

  private void getYarnRmApp( ContentType contentType, boolean running ) throws Exception {
    String username = "hdfs";
    String password = "hdfs-password";
    String path = "/v1/cluster/apps/application_1399541193872_0033/";
    String resource;
    if ( running ) {
      resource = "/yarn/app_running";
    } else {
      resource = "/yarn/app_succeeded";
    }

    switch( contentType ) {
    case JSON:
      resource += ".json";
      break;
    case XML:
      resource += ".xml";
      break;
    default:
      break;
    }
    String gatewayPath = driver.getUrl( "RESOURCEMANAGER" ) + path + (driver.isUseGateway() ? "" : "?user.name=" + username);
    InetSocketAddress gatewayAddress = driver.gateway.getAddresses()[0];
    String gatewayHostName = gatewayAddress.getHostName();
    String gatewayAddrName = InetAddress.getByName( gatewayHostName ).getHostAddress();

    VelocityEngine velocity = new VelocityEngine();
    velocity.setProperty( RuntimeConstants.RUNTIME_LOG_LOGSYSTEM_CLASS, "org.apache.velocity.runtime.log.NullLogSystem" );
    velocity.setProperty( RuntimeConstants.RESOURCE_LOADER, "classpath" );
    velocity.setProperty( "classpath.resource.loader.class", ClasspathResourceLoader.class.getName() );
    velocity.init();

    VelocityContext context = new VelocityContext();
    context.put( "proxy_address", driver.getRealUrl( "RESOURCEMANAGER" ) );

    String name = TestUtils.getResourceName( this.getClass(), resource );
    Template template = velocity.getTemplate( name );
    StringWriter sw = new StringWriter();
    template.merge( context, sw );
    String request = sw.toString();

    driver.getMock( "RESOURCEMANAGER" ).expect().method( "GET" )
        .pathInfo( path ).queryParam( "user.name", username ).respond()
        .status( HttpStatus.SC_OK )
        .content( request.getBytes() )
        .contentType( contentType.toString() );

    ResponseSpecification response = given()
//         .log().all()
        .auth()
        .preemptive()
        .basic( username, password )
        .header( "X-XSRF-Header", "jksdhfkhdsf" )
        .expect()
//         .log().all()
        .statusCode( HttpStatus.SC_OK )
        .contentType( contentType );
    if ( running ) {
      response.content(
          "app.trackingUrl",
          anyOf(
              startsWith( "http://" + gatewayHostName + ":" + gatewayAddress.getPort() + "/" ),
              startsWith( "http://" + gatewayAddrName + ":" + gatewayAddress.getPort() + "/" ) ) );
    } else {
      response.content( "app.trackingUrl", isEmptyString() );
    }

    response.content( "app.amContainerLogs", isEmptyString() )
        .content( "app.amHostHttpAddress", isEmptyString() )
        .when()
        .get( gatewayPath );

    driver.assertComplete();
  }

  private void getYarnRmResource( String path, ContentType contentType, String resource )
      throws Exception {

    String username = "hdfs";
    String password = "hdfs-password";

    switch( contentType ) {
    case JSON:
      resource += ".json";
      break;
    case XML:
      resource += ".xml";
      break;
    default:
      break;
    }

    driver.getMock( "RESOURCEMANAGER" ).expect().method( "GET" )
        .pathInfo( path ).queryParam( "user.name", username ).respond()
        .status( HttpStatus.SC_OK )
        .content( driver.getResourceBytes( resource ) )
        .contentType( contentType.toString() );

    Response response = given()
//         .log().all()
        .auth()
        .preemptive()
        .basic( username, password )
        .header( "X-XSRF-Header", "jksdhfkhdsf" )
        .expect()
//         .log().all()
        .statusCode( HttpStatus.SC_OK )
        .contentType( contentType )
        .when()
        .get(
            driver.getUrl( "RESOURCEMANAGER" ) + path
                + (driver.isUseGateway() ? "" : "?user.name=" + username) );

    switch( contentType ) {
    case JSON:
      MatcherAssert.assertThat( response.getBody().asString(),
          sameJSONAs( driver.getResourceString( resource, UTF8 ) ) );
      break;
    case XML:
      MatcherAssert
      .assertThat( the( response.getBody().asString() ),
          isEquivalentTo( the( driver.getResourceString( resource, UTF8 ) ) ) );
      break;
    default:
      break;
    }
    driver.assertComplete();
  }

  @Test
  public void testYarnRmAppattempts() throws Exception {
    getYarnRmAppattempts( ContentType.JSON );
    getYarnRmAppattempts( ContentType.XML );
  }

  private void getYarnRmAppattempts( ContentType contentType ) throws Exception {
    String username = "hdfs";
    String password = "hdfs-password";
    String path = "/v1/cluster/apps/application_1399541193872_0018/appattempts/";
    String resource = "/yarn/appattempts";
    String gatewayPath = driver.getUrl( "RESOURCEMANAGER" ) + path + (driver.isUseGateway() ? "" : "?user.name=" + username);

    switch( contentType ) {
    case JSON:
      resource += ".json";
      break;
    case XML:
      resource += ".xml";
      break;
    default:
      break;
    }

    driver.getMock( "RESOURCEMANAGER" ).expect().method( "GET" )
        .pathInfo( path ).queryParam( "user.name", username ).respond()
        .status( HttpStatus.SC_OK )
        .content( driver.getResourceBytes( resource ) )
        .contentType( contentType.toString() );

    given()
//         .log().all()
        .auth()
        .preemptive()
        .basic( username, password )
        .header( "X-XSRF-Header", "jksdhfkhdsf" )
        .expect()
//         .log().all()
        .statusCode( HttpStatus.SC_OK )
        .contentType( contentType )
        .content( "appAttempts.appAttempt[0].nodeHttpAddress", isEmptyString() )
        .content( "appAttempts.appAttempt[0].nodeId", not( containsString( "localhost:50060" ) ) )
        .content( "appAttempts.appAttempt[0].logsLink", isEmptyString() )
        .when()
        .get( gatewayPath );

    driver.assertComplete();
  }

  @Test
  public void testYarnRmNodes() throws Exception {
    getYarnRmNodes( ContentType.JSON, null );
    getYarnRmNodes( ContentType.XML, null );

    Map<String, String> params = new HashMap<String, String>();
    params.put( "state", "new,running" );
    params.put( "healthy", "true" );

    getYarnRmNodes( ContentType.JSON, params );
    getYarnRmNodes( ContentType.XML, params );
  }

  private void getYarnRmNodes( ContentType contentType, Map<String, String> params ) throws Exception {
    String username = "hdfs";
    String password = "hdfs-password";
    String path = "/v1/cluster/nodes/";
    String nodesResource = "/yarn/nodes";
    String nodeResource = "/yarn/node";
    String nodeId = "localhost:45454";
    String gatewayPath = driver.getUrl( "RESOURCEMANAGER" ) + path;
    String gatewayPathQuery = driver.isUseGateway() ? "" : "?user.name=" + username;


    MockRequestMatcher mockRequestMatcher = driver.getMock( "RESOURCEMANAGER" ).expect().method( "GET" )
        .pathInfo( path ).queryParam( "user.name", username );

    if ( params != null ) {
      for (Entry<String, String> param : params.entrySet()) {
        mockRequestMatcher.queryParam( param.getKey(), param.getValue() );
        if (gatewayPathQuery.isEmpty()) {
          gatewayPathQuery += "?";
        } else {
          gatewayPathQuery += "&";
        }
        gatewayPathQuery += param.getKey() + "=" + param.getValue();
      }
    }

    mockRequestMatcher.respond()
        .status( HttpStatus.SC_OK )
        .content( driver.getResourceBytes( nodesResource + (contentType == ContentType.JSON ? ".json" : ".xml" ) ) )
        .contentType( contentType.toString() );

    String encryptedNodeId = given()
//         .log().all()
        .auth()
        .preemptive()
        .basic( username, password )
        .header( "X-XSRF-Header", "jksdhfkhdsf" )
        .expect()
//         .log().all()
        .statusCode( HttpStatus.SC_OK )
        .contentType( contentType )
        .content( "nodes.node[0].id", not( containsString( nodeId ) ) )
        .content( "nodes.node[0].nodeHostName", isEmptyString() )
        .content( "nodes.node[0].nodeHTTPAddress", isEmptyString() )
        .when()
        .get( gatewayPath + gatewayPathQuery ).getBody().path( "nodes.node[0].id" );

    driver.assertComplete();

    driver.getMock( "RESOURCEMANAGER" ).expect().method( "GET" )
        .pathInfo( path + nodeId ).queryParam( "user.name", username ).respond()
        .status( HttpStatus.SC_OK )
        .content( driver.getResourceBytes( nodeResource + (contentType == ContentType.JSON ? ".json" : ".xml" ) ) )
        .contentType( contentType.toString() );

    given()
//         .log().all()
        .auth()
        .preemptive()
        .basic( username, password )
        .header( "X-XSRF-Header", "jksdhfkhdsf" )
        .expect()
//         .log().all()
        .statusCode( HttpStatus.SC_OK )
        .contentType( contentType )
        .content( "node.id", not( containsString( nodeId ) ) )
        .content( "node.nodeHostName", isEmptyString() )
        .content( "node.nodeHTTPAddress", isEmptyString() )
        .when()
        .get( gatewayPath + encryptedNodeId );

    driver.assertComplete();
  }

  @Test
  public void testYarnRmProxy() throws Exception {
    String username = "hdfs";
    String password = "hdfs-password";
    String path = "/v1/cluster/apps/application_1399541193872_0033/";
    String gatewayPath = driver.getUrl( "RESOURCEMANAGER" ) + path;

    Map<String, Matcher<?>> matchers = new HashMap<String, Matcher<?>>();

    VelocityEngine velocity = new VelocityEngine();
    velocity.setProperty( RuntimeConstants.RUNTIME_LOG_LOGSYSTEM_CLASS, "org.apache.velocity.runtime.log.NullLogSystem" );
    velocity.setProperty( RuntimeConstants.RESOURCE_LOADER, "classpath" );
    velocity.setProperty( "classpath.resource.loader.class", ClasspathResourceLoader.class.getName() );
    velocity.init();

    VelocityContext context = new VelocityContext();
    context.put( "proxy_address", driver.getRealUrl( "RESOURCEMANAGER" ) );

    String name = TestUtils.getResourceName( this.getClass(), "yarn/app_running.json" );
    Template template = velocity.getTemplate( name );
    StringWriter sw = new StringWriter();
    template.merge( context, sw );
    String request = sw.toString();

    driver.getMock( "RESOURCEMANAGER" ).expect().method( "GET" )
        .pathInfo( path )
        .queryParam( "user.name", username ).respond()
        .status( HttpStatus.SC_OK )
        .content( request.getBytes() )
        .contentType( ContentType.JSON.toString() );

    String encryptedTrackingUrl = given()
        // .log().all()
        .auth().preemptive().basic( username, password )
        .header( "X-XSRF-Header", "jksdhfkhdsf" )
        .expect()
        // .log().all()
        .statusCode( HttpStatus.SC_OK ).contentType( ContentType.JSON ).when()
        .get( gatewayPath + ( driver.isUseGateway() ? "" : "?user.name=" + username ) ).getBody()
        .path( "app.trackingUrl" );

    String encryptedQuery = new URI( encryptedTrackingUrl ).getQuery();

    driver.assertComplete();

    // Test that root address of MapReduce Application Master REST API is not accessible through Knox
    // For example, https://{gateway_host}:{gateway_port}/gateway/{cluster}/resourcemanager/proxy/{app_id}/?_={encrypted_application_proxy_location} should return Not Found response
    //  https://{gateway_host}:{gateway_port}/gateway/{cluster}/resourcemanager/proxy/{app_id}/ws/v1/mapreduce/?_={encrypted_application_proxy_location} returns OK
    given()
        // .log().all()
        .auth().preemptive().basic( username, password )
        .header( "X-XSRF-Header", "jksdhfkhdsf" ).expect()
        // .log().all()
        .statusCode( HttpStatus.SC_NOT_FOUND ).when()
        .get( encryptedTrackingUrl );

    String resource = null;

    path = "/proxy/application_1399541193872_0033/ws/v1/mapreduce/info";
    resource = "yarn/proxy-mapreduce-info";
    getYarnRmProxyData( encryptedQuery, path, resource, ContentType.JSON );
    getYarnRmProxyData( encryptedQuery, path, resource, ContentType.XML );
    path = "/proxy/application_1399541193872_0033/ws/v1/mapreduce";
    getYarnRmProxyData( encryptedQuery, path, resource, ContentType.JSON );
    getYarnRmProxyData( encryptedQuery, path, resource, ContentType.XML );

    path = "/proxy/application_1399541193872_0033/ws/v1/mapreduce/jobs";
    resource = "yarn/proxy-mapreduce-jobs";
    getYarnRmProxyData( encryptedQuery, path, resource, ContentType.JSON );
    getYarnRmProxyData( encryptedQuery, path, resource, ContentType.XML );

    path = "/proxy/application_1399541193872_0033/ws/v1/mapreduce/jobs/job_1399541193872_0035";
    resource = "yarn/proxy-mapreduce-job";
    getYarnRmProxyData( encryptedQuery, path, resource, ContentType.JSON );
    getYarnRmProxyData( encryptedQuery, path, resource, ContentType.XML );

    path = "/proxy/application_1399541193872_0033/ws/v1/mapreduce/jobs/job_1399541193872_0035/counters";
    resource = "yarn/proxy-mapreduce-job-counters";
    getYarnRmProxyData( encryptedQuery, path, resource, ContentType.JSON );
    getYarnRmProxyData( encryptedQuery, path, resource, ContentType.XML );



//    TODO: Need to understand what we should do with following properties
//    hadoop.proxyuser.HTTP.hosts
//    dfs.namenode.secondary.http-address
//    dfs.namenode.http-address
//    mapreduce.jobhistory.webapp.address
//    mapreduce.jobhistory.webapp.https.address
//    dfs.namenode.https-address
//    mapreduce.job.submithostname
//    yarn.resourcemanager.webapp.address
//    yarn.resourcemanager.hostname
//    mapreduce.jobhistory.address
//    yarn.resourcemanager.webapp.https.address
//    hadoop.proxyuser.oozie.hosts
//    hadoop.proxyuser.hive.hosts
//    dfs.namenode.secondary.https-address
//    hadoop.proxyuser.hcat.hosts
//    hadoop.proxyuser.HTTP.hosts
//    TODO: resolve java.util.regex.PatternSyntaxException: Unmatched closing ')' near index 17   m@\..*EXAMPLE\.COM)s
    path = "/proxy/application_1399541193872_0035/ws/v1/mapreduce/jobs/job_1399541193872_0035/conf";
    resource = "yarn/proxy-mapreduce-job-conf";
//    getYarnRmProxyJobConf( encryptedQuery, path, resource, ContentType.JSON );
//    getYarnRmProxyJobConf( encryptedQuery, path, resource, ContentType.XML );


    path = "/proxy/application_1399541193872_0036/ws/v1/mapreduce/jobs/job_1399541193872_0036/jobattempts";
    resource = "yarn/proxy-mapreduce-job-attempts";
    matchers.clear();
    matchers.put( "jobAttempts.jobAttempt[0].nodeHttpAddress", isEmptyString() );
    matchers.put( "jobAttempts.jobAttempt[0].nodeId", not( containsString( "host.yarn.com:45454" ) ) );
    matchers.put( "jobAttempts.jobAttempt[0].logsLink", isEmptyString() );
    getYarnRmProxyData( encryptedQuery, path, resource, ContentType.JSON, matchers );
    getYarnRmProxyData( encryptedQuery, path, resource, ContentType.XML, matchers );

    path = "/proxy/application_1399541193872_0036/ws/v1/mapreduce/jobs/job_1399541193872_0036/tasks";
    resource = "yarn/proxy-mapreduce-tasks";
    getYarnRmProxyData( encryptedQuery, path, resource, ContentType.JSON );
    getYarnRmProxyData( encryptedQuery, path, resource, ContentType.XML );

    path = "/proxy/application_1399541193872_0036/ws/v1/mapreduce/jobs/job_1399541193872_0036/tasks/task_1399541193872_0036_r_00";
    resource = "yarn/proxy-mapreduce-task";
    getYarnRmProxyData( encryptedQuery, path, resource, ContentType.JSON );
    getYarnRmProxyData( encryptedQuery, path, resource, ContentType.XML );

    path = "/proxy/application_1399541193872_0036/ws/v1/mapreduce/jobs/job_1399541193872_0036/tasks/task_1399541193872_0036_r_00/counters";
    resource = "yarn/proxy-mapreduce-task-counters";
    getYarnRmProxyData( encryptedQuery, path, resource, ContentType.JSON );
    getYarnRmProxyData( encryptedQuery, path, resource, ContentType.XML );


    path = "/proxy/application_1399541193872_0036/ws/v1/mapreduce/jobs/job_1399541193872_0036/tasks/task_1399541193872_0036_r_00/attempts";
    resource = "yarn/proxy-mapreduce-task-attempts";
    matchers.clear();
    matchers.put( "taskAttempts.taskAttempt[0].nodeHttpAddress", isEmptyString() );
    getYarnRmProxyData( encryptedQuery, path, resource, ContentType.JSON, matchers );
    getYarnRmProxyData( encryptedQuery, path, resource, ContentType.XML, matchers );

    path = "/proxy/application_1399541193872_0036/ws/v1/mapreduce/jobs/job_1399541193872_0036/tasks/task_1399541193872_0036_r_00/attempts/attempt_1399541193872_0036_r_000000_0";
    resource = "yarn/proxy-mapreduce-task-attempt";
    matchers.clear();
    matchers.put( "taskAttempt.nodeHttpAddress", isEmptyString() );
    getYarnRmProxyData( encryptedQuery, path, resource, ContentType.JSON, matchers );
    getYarnRmProxyData( encryptedQuery, path, resource, ContentType.XML, matchers );

    path = "/proxy/application_1399541193872_0036/ws/v1/mapreduce/jobs/job_1399541193872_0036/tasks/task_1399541193872_0036_r_00/attempts/attempt_1399541193872_0036_r_000000_0/counters";
    resource = "yarn/proxy-mapreduce-task-attempt-counters";
    getYarnRmProxyData( encryptedQuery, path, resource, ContentType.JSON );
    getYarnRmProxyData( encryptedQuery, path, resource, ContentType.XML );

  }

  private void getYarnRmProxyData( String encryptedQuery, String path, String resource, ContentType contentType ) throws Exception {
    getYarnRmProxyData( encryptedQuery, path, resource, contentType, null );
  }

  private void getYarnRmProxyData( String encryptedQuery, String path, String resource, ContentType contentType, Map<String, Matcher<?>> contentMatchers ) throws Exception {

    String username = "hdfs";
    String password = "hdfs-password";
    String gatewayPath = driver.getUrl( "RESOURCEMANAGER" ) + path + "?" + encryptedQuery + ( driver.isUseGateway() ? "" : "&user.name=" + username );

    switch( contentType ) {
    case JSON:
      resource += ".json";
      break;
    case XML:
      resource += ".xml";
      break;
    default:
      break;
    }

    driver.getMock( "RESOURCEMANAGER" ).expect().method( "GET" )
    .pathInfo( path )
    .queryParam( "user.name", username ).respond()
    .status( HttpStatus.SC_OK )
    .content( driver.getResourceBytes( resource ) )
    .contentType( contentType.toString() );

    ResponseSpecification responseSpecification = given()
//     .log().all()
    .auth().preemptive().basic( username, password )
    .header( "X-XSRF-Header", "jksdhfkhdsf" )
    .expect()
//     .log().all()
    .statusCode( HttpStatus.SC_OK ).contentType( contentType );

    if ( contentMatchers != null ) {
      for ( Entry<String, Matcher<?>> matcher : contentMatchers.entrySet() ) {
        responseSpecification.content( matcher.getKey(), matcher.getValue() );
      }
    }

    Response response = responseSpecification.when().get( gatewayPath );

    if ( contentMatchers == null || contentMatchers.isEmpty() ) {
      switch( contentType ) {
      case JSON:
        MatcherAssert.assertThat( response.getBody().asString(),
            sameJSONAs( driver.getResourceString( resource, UTF8 ) ) );
        break;
      case XML:
        MatcherAssert
        .assertThat( the( response.getBody().asString() ),
            isEquivalentTo( the( driver.getResourceString( resource, UTF8 ) ) ) );
        break;
      default:
        break;
      }
    }

    driver.assertComplete();
  }

  @SuppressWarnings("unused")
  private void getYarnRmProxyJobConf( String encryptedQuery, String path, String resource, ContentType contentType ) throws Exception {

    String username = "hdfs";
    String password = "hdfs-password";
    String gatewayPath = driver.getUrl( "RESOURCEMANAGER" ) + path + "?" + encryptedQuery + ( driver.isUseGateway() ? "" : "&user.name=" + username );

    switch( contentType ) {
    case JSON:
      resource += ".json";
      break;
    case XML:
      resource += ".xml";
      break;
    default:
      break;
    }

    driver.getMock( "RESOURCEMANAGER" ).expect().method( "GET" )
    .pathInfo( path )
    .queryParam( "user.name", username ).respond()
    .status( HttpStatus.SC_OK )
    .content( driver.getResourceBytes( resource ) )
    .contentType( contentType.toString() );

    Response response = given()
//     .log().all()
    .auth().preemptive().basic( username, password )
    .header( "X-XSRF-Header", "jksdhfkhdsf" )
    .expect()
//     .log().all()
    .statusCode( HttpStatus.SC_OK ).contentType( contentType ).when()
    .get( gatewayPath );

    assertThat( response.body().asString(), not( containsString( "host.yarn.com" ) ) );

    driver.assertComplete();
  }

  private File findFile( File dir, String pattern ) {
    File file = null;
    FileFilter filter = new WildcardFileFilter( pattern );
    File[] files = dir.listFiles(filter);
    if( files != null && files.length > 0 ) {
      file = files[0];
    }
    return file;
  }

  private String findHadoopExamplesJar() throws IOException {
    String pattern = "hadoop-examples-*.jar";
    File dir = new File( System.getProperty( "user.dir" ), "hadoop-examples/target" );
    File file = findFile( dir, pattern );
    if( file == null || !file.exists() ) {
      file = findFile( new File( System.getProperty( "user.dir" ), "../hadoop-examples/target" ), pattern );
    }
    if( file == null ) {
      throw new FileNotFoundException( pattern );
    }
    return file.toURI().toString();
  }
}
