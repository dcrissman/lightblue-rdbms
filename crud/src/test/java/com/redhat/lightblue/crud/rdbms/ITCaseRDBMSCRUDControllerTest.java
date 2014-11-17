/*
 Copyright 2013 Red Hat, Inc. and/or its affiliates.

 This file is part of lightblue.

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.redhat.lightblue.crud.rdbms;

import static com.redhat.lightblue.util.JsonUtils.json;
import static com.redhat.lightblue.util.test.AbstractJsonNodeTest.loadJsonNode;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.FileReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.sql.DataSource;

import org.h2.tools.RunScript;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.redhat.lightblue.EntityVersion;
import com.redhat.lightblue.common.rdbms.RDBMSDataSourceResolver;
import com.redhat.lightblue.common.rdbms.RDBMSDataStore;
import com.redhat.lightblue.crud.CRUDInsertionResponse;
import com.redhat.lightblue.crud.CRUDOperationContext;
import com.redhat.lightblue.crud.Factory;
import com.redhat.lightblue.crud.InsertionRequest;
import com.redhat.lightblue.crud.Operation;
import com.redhat.lightblue.mediator.OperationContext;
import com.redhat.lightblue.metadata.EntityConstraint;
import com.redhat.lightblue.metadata.EntityInfo;
import com.redhat.lightblue.metadata.EntityMetadata;
import com.redhat.lightblue.metadata.Metadata;
import com.redhat.lightblue.metadata.parser.Extensions;
import com.redhat.lightblue.metadata.parser.FieldConstraintParser;
import com.redhat.lightblue.metadata.parser.JSONMetadataParser;
import com.redhat.lightblue.metadata.rdbms.impl.RDBMSDataStoreParser;
import com.redhat.lightblue.metadata.rdbms.impl.RDBMSPropertyParserImpl;
import com.redhat.lightblue.metadata.types.DefaultTypes;
import com.redhat.lightblue.query.Projection;
import com.redhat.lightblue.test.metadata.SimpleMetadata;


public class ITCaseRDBMSCRUDControllerTest{

    private Connection connection;
    private RDBMSCRUDController controller;

    @Before
    public void before() throws Exception {
        connection = DriverManager.getConnection("jdbc:h2:mem:rdbmstest", "", "");

        /*
         * This next section is admittedly odd, but it prevents lightblue from
         * calling the Connection.close() method so that assertions can be run against
         * the in memory database.
         */
        Connection spyConnection = spy(connection);
        doAnswer(new Answer<Void>() {

            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                return null;
            }

        }).when(spyConnection).close();


        DataSource mockDataSource = mock(DataSource.class);
        when(mockDataSource.getConnection()).thenReturn(spyConnection);

        RunScript.execute(connection,
                new FileReader(getClass().getResource("/crud/employee.sql").getFile()));

        controller = new RDBMSCRUDController(new TestRDBMSDataSourceResolver(mockDataSource));
    }

    @After
    public void after() throws SQLException{
        if(!connection.isClosed()){
            connection.close();
        }
    }

    @Test
    public void testInsert() throws Exception {
        JsonNode node = loadJsonNode("./crud/sample.json");
        Metadata metadata = loadMetadata(node, null, null);

        JsonNode insertNode = loadJsonNode("./crud/insert.json");

        InsertionRequest request = new InsertionRequest();
        request.setEntityVersion(new EntityVersion("test", "1.0"));
        request.setEntityData(insertNode);

        CRUDOperationContext crudOperationContext = OperationContext.getInstance(request, metadata, new Factory(), Operation.INSERT);
        Projection projection = Projection.fromJson(json("{\"field\":\"employeeid\"}"));

        CRUDInsertionResponse response = controller.insert(crudOperationContext, projection);

        assertNotNull(response);
        assertFalse(crudOperationContext.hasErrors());

        ResultSet rs = connection.createStatement().executeQuery("SELECT id, name FROM employee WHERE id = 12345");
        rs.next();
        assertEquals(12345, rs.getInt("id"));
        assertEquals("Neo", rs.getString("name"));

        //assertEquals(1, response.getNumInserted());
    }

    protected Metadata loadMetadata(
            JsonNode node,
            List<? extends EntityConstraint> entityConstraints,
            Map<String, ? extends FieldConstraintParser<JsonNode>> fieldConstraintParsers){

        RDBMSDataStoreParser<JsonNode> dsParser = new RDBMSDataStoreParser<JsonNode>();
        Extensions<JsonNode> extensions = new Extensions<JsonNode>();
        extensions.registerDataStoreParser(dsParser.getDefaultName(), dsParser);
        extensions.registerPropertyParser(RDBMSPropertyParserImpl.NAME, new RDBMSPropertyParserImpl<JsonNode>());
        extensions.addDefaultExtensions();
        if(fieldConstraintParsers != null){
            for(Entry<String, ? extends FieldConstraintParser<JsonNode>> checker : fieldConstraintParsers.entrySet()){
                extensions.registerFieldConstraintParser(checker.getKey(), checker.getValue());
            }
        }
        JSONMetadataParser jsonParser = new JSONMetadataParser(
                extensions,
                new DefaultTypes(),
                JsonNodeFactory.withExactBigDecimals(false));

        SimpleMetadata metadata = new SimpleMetadata();

        EntityMetadata entityMetadata = jsonParser.parseEntityMetadata(node);
        if((entityConstraints != null) && !entityConstraints.isEmpty()){
            entityMetadata.setConstraints(new ArrayList<EntityConstraint>(entityConstraints));
        }

        EntityInfo info = entityMetadata.getEntityInfo();

        metadata.setEntityInfo(info);
        String version = entityMetadata.getVersion().getValue();
        metadata.setEntityMetadata(info.getName(), version, entityMetadata);

        return metadata;
    }

    protected static class TestRDBMSDataSourceResolver implements RDBMSDataSourceResolver{

        private final DataSource ds;

        public TestRDBMSDataSourceResolver(DataSource ds){
            this.ds = ds;
        }

        @Override
        public DataSource get(RDBMSDataStore store) {
            return ds;
        }

    }

}
