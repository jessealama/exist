package org.exist.indexing;

import org.exist.collections.Collection;
import org.exist.collections.CollectionConfigurationManager;
import org.exist.collections.IndexInfo;
import org.exist.dom.NodeProxy;
import org.exist.security.xacml.AccessContext;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.serializers.EXistOutputKeys;
import org.exist.storage.serializers.Serializer;
import org.exist.storage.txn.TransactionManager;
import org.exist.storage.txn.Txn;
import org.exist.test.TestConstants;
import org.exist.util.Configuration;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQuery;
import org.exist.xquery.value.Sequence;
import org.junit.AfterClass;
import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;
import org.junit.BeforeClass;
import org.junit.Test;
import org.xml.sax.SAXException;
import org.custommonkey.xmlunit.XMLAssert;

import javax.xml.transform.OutputKeys;
import java.util.Properties;

public class MatchListenerTest {

    private static String XML =
        "<root>" +
        "   <para>some paragraph with <hi>mixed</hi> content.</para>" +
        "   <para>another paragraph with <note><hi>nested</hi> inner</note> elements.</para>" +
        "   <para>a third paragraph with <term>term</term>.</para>" +
        "   <para>double match double match</para>" +
        "</root>";

    private static String CONF1 =
        "<collection xmlns=\"http://exist-db.org/collection-config/1.0\">" +
    	"	<index>" +
    	"		<fulltext default=\"none\">" +
    	"		</fulltext>" +
    	"		<ngram qname=\"para\"/>" +
        "		<ngram qname=\"term\"/>" +
        "	</index>" +
    	"</collection>";

    private static String CONF2 =
        "<collection xmlns=\"http://exist-db.org/collection-config/1.0\">" +
    	"	<index>" +
    	"		<fulltext default=\"none\">" +
    	"		</fulltext>" +
    	"		<ngram qname=\"note\"/>" +
        "	</index>" +
    	"</collection>";

    private static String MATCH_START = "<exist:match xmlns:exist=\"http://exist.sourceforge.net/NS/exist\">";
    private static String MATCH_END = "</exist:match>";

    private static BrokerPool pool;

    @Test
    public void nestedContent() {
        DBBroker broker = null;
        try {
            configureAndStore(CONF1);

            broker = pool.get(org.exist.security.SecurityManager.SYSTEM_USER);

            XQuery xquery = broker.getXQueryService();
            assertNotNull(xquery);
            Sequence seq = xquery.execute("//para[text:ngram-contains(., 'mixed')]", null, AccessContext.TEST);
            assertNotNull(seq);
            assertEquals(1, seq.getItemCount());
            String result = queryResult2String(broker, seq);
            System.out.println("RESULT: " + result);
            XMLAssert.assertEquals("<para>some paragraph with <hi>" + MATCH_START + "mixed" +
                    MATCH_END + "</hi> content.</para>", result);

            seq = xquery.execute("//para[text:ngram-contains(., 'content')]", null, AccessContext.TEST);
            assertNotNull(seq);
            assertEquals(1, seq.getItemCount());
            result = queryResult2String(broker, seq);
            System.out.println("RESULT: " + result);
            XMLAssert.assertEquals("<para>some paragraph with <hi>mixed</hi> " + MATCH_START + "content" +
                    MATCH_END + ".</para>", result);

            seq = xquery.execute("//para[text:ngram-contains(., 'nested')]", null, AccessContext.TEST);
            assertNotNull(seq);
            assertEquals(1, seq.getItemCount());
            result = queryResult2String(broker, seq);
            System.out.println("RESULT: " + result);
            XMLAssert.assertEquals("<para>another paragraph with <note><hi>" + MATCH_START + "nested" + MATCH_END +
                    "</hi> inner</note> elements.</para>", result);

            seq = xquery.execute("//para[text:ngram-contains(., 'content') and text:ngram-contains(., 'mixed')]", null, AccessContext.TEST);
            assertNotNull(seq);
            assertEquals(1, seq.getItemCount());
            result = queryResult2String(broker, seq);
            System.out.println("RESULT: " + result);
            XMLAssert.assertEquals("<para>some paragraph with <hi>" + MATCH_START + "mixed" + MATCH_END +
                    "</hi> " + MATCH_START + "content" + MATCH_END + ".</para>", result);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        } finally {
            pool.release(broker);
        }
    }

    @Test
    public void matchInParent() {
        DBBroker broker = null;
        try {
            configureAndStore(CONF1);

            broker = pool.get(org.exist.security.SecurityManager.SYSTEM_USER);

            XQuery xquery = broker.getXQueryService();
            assertNotNull(xquery);
            Sequence seq = xquery.execute("//para[text:ngram-contains(., 'mixed')]/hi", null, AccessContext.TEST);
            assertNotNull(seq);
            assertEquals(1, seq.getItemCount());
            String result = queryResult2String(broker, seq);
            System.out.println("RESULT: " + result);
            XMLAssert.assertEquals("<hi>" + MATCH_START + "mixed" + MATCH_END + "</hi>", result);

        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        } finally {
            pool.release(broker);
        }
    }

    @Test
    public void matchInAncestor() {
        DBBroker broker = null;
        try {
            configureAndStore(CONF1);

            broker = pool.get(org.exist.security.SecurityManager.SYSTEM_USER);

            XQuery xquery = broker.getXQueryService();
            assertNotNull(xquery);
            Sequence seq = xquery.execute("//para[text:ngram-contains(., 'nested')]/note", null, AccessContext.TEST);
            assertNotNull(seq);
            assertEquals(1, seq.getItemCount());
            String result = queryResult2String(broker, seq);
            System.out.println("RESULT: " + result);
            XMLAssert.assertEquals("<note><hi>" + MATCH_START + "nested" + MATCH_END + "</hi> inner</note>", result);

            seq = xquery.execute("//para[text:ngram-contains(., 'nested')]//hi", null, AccessContext.TEST);
            assertNotNull(seq);
            assertEquals(1, seq.getItemCount());
            result = queryResult2String(broker, seq);
            System.out.println("RESULT: " + result);
            XMLAssert.assertEquals("<hi>" + MATCH_START + "nested" + MATCH_END + "</hi>", result);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        } finally {
            pool.release(broker);
        }
    }

    @Test
    public void nestedIndex() {
        DBBroker broker = null;
        try {
            configureAndStore(CONF1);

            broker = pool.get(org.exist.security.SecurityManager.SYSTEM_USER);

            XQuery xquery = broker.getXQueryService();
            assertNotNull(xquery);
            Sequence seq = xquery.execute("//para[text:ngram-contains(term, 'term')]", null, AccessContext.TEST);
            assertNotNull(seq);
            assertEquals(1, seq.getItemCount());
            String result = queryResult2String(broker, seq);
            System.out.println("RESULT: " + result);
            XMLAssert.assertEquals("<para>a third paragraph with <term>" + MATCH_START + "term" + MATCH_END + "</term>.</para>", result);

            seq = xquery.execute("//term[text:ngram-contains(., 'term')]/..", null, AccessContext.TEST);
            assertNotNull(seq);
            assertEquals(1, seq.getItemCount());
            result = queryResult2String(broker, seq);
            System.out.println("RESULT: " + result);
            XMLAssert.assertEquals("<para>a third paragraph with <term>" + MATCH_START + "term" + MATCH_END + "</term>.</para>", result);

            seq = xquery.execute("//term[text:ngram-contains(., 'term')]/ancestor::para", null, AccessContext.TEST);
            assertNotNull(seq);
            assertEquals(1, seq.getItemCount());
            result = queryResult2String(broker, seq);
            System.out.println("RESULT: " + result);
            XMLAssert.assertEquals("<para>a third paragraph with <term>" + MATCH_START + "term" + MATCH_END + "</term>.</para>", result);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        } finally {
            pool.release(broker);
        }
    }

    @Test
    public void mixedContentQueries() {
        DBBroker broker = null;
        try {
            configureAndStore(CONF1);
            broker = pool.get(org.exist.security.SecurityManager.SYSTEM_USER);

            XQuery xquery = broker.getXQueryService();
            assertNotNull(xquery);
            Sequence seq = xquery.execute("//para[text:ngram-contains(., 'mixed content')]", null, AccessContext.TEST);
            assertNotNull(seq);
            assertEquals(1, seq.getItemCount());
            String result = queryResult2String(broker, seq);
            System.out.println("RESULT: " + result);
            XMLAssert.assertEquals("<para>some paragraph with <hi>" + MATCH_START + "mixed" +
                    MATCH_END + "</hi>" + MATCH_START + " content" + MATCH_END + ".</para>", result);

            seq = xquery.execute("//para[text:ngram-contains(., 'with mixed content')]", null, AccessContext.TEST);
            assertNotNull(seq);
            assertEquals(1, seq.getItemCount());
            result = queryResult2String(broker, seq);
            System.out.println("RESULT: " + result);
            XMLAssert.assertEquals("<para>some paragraph " + MATCH_START + "with " + MATCH_END + "<hi>" +
                    MATCH_START + "mixed" + MATCH_END + "</hi>" + MATCH_START + " content" + MATCH_END +
                ".</para>", result);

            seq = xquery.execute("//para[text:ngram-contains(., 'with nested')]", null, AccessContext.TEST);
            assertNotNull(seq);
            assertEquals(1, seq.getItemCount());
            result = queryResult2String(broker, seq);
            System.out.println("RESULT: " + result);
            XMLAssert.assertEquals("<para>another paragraph " + MATCH_START + "with " + MATCH_END +
                "<note><hi>" + MATCH_START + "nested" + MATCH_END + "</hi> inner</note> elements.</para>", result);

            seq = xquery.execute("//para[text:ngram-contains(., 'with nested inner elements')]", null, AccessContext.TEST);
            assertNotNull(seq);
            assertEquals(1, seq.getItemCount());
            result = queryResult2String(broker, seq);
            System.out.println("RESULT: " + result);
            XMLAssert.assertEquals("<para>another paragraph " + MATCH_START + "with " + MATCH_END +
                "<note><hi>" + MATCH_START + "nested" + MATCH_END + "</hi>" + MATCH_START + " inner" + MATCH_END +
                "</note>" + MATCH_START + " elements" + MATCH_END + ".</para>", result);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        } finally {
            pool.release(broker);
        }
    }

    @Test
    public void indexOnInnerElement() {
        DBBroker broker = null;
        try {
            configureAndStore(CONF2);
            broker = pool.get(org.exist.security.SecurityManager.SYSTEM_USER);

            XQuery xquery = broker.getXQueryService();
            assertNotNull(xquery);
            Sequence seq = xquery.execute("//para[text:ngram-contains(note, 'nested inner')]", null, AccessContext.TEST);
            assertNotNull(seq);
            assertEquals(1, seq.getItemCount());
            String result = queryResult2String(broker, seq);
            System.out.println("RESULT: " + result);
            XMLAssert.assertEquals("<para>another paragraph with <note><hi>" + MATCH_START + "nested" + MATCH_END +
                "</hi>" + MATCH_START + " inner" + MATCH_END + "</note> elements.</para>", result);

            seq = xquery.execute("//note[text:ngram-contains(., 'nested inner')]/parent::para", null, AccessContext.TEST);
            assertNotNull(seq);
            assertEquals(1, seq.getItemCount());
            result = queryResult2String(broker, seq);
            System.out.println("RESULT: " + result);
            XMLAssert.assertEquals("<para>another paragraph with <note><hi>" + MATCH_START + "nested" + MATCH_END +
                "</hi>" + MATCH_START + " inner" + MATCH_END + "</note> elements.</para>", result);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        } finally {
            pool.release(broker);
        }
    }

    @Test
    public void doubleMatch() {
        DBBroker broker = null;
        try {
            configureAndStore(CONF1);
            broker = pool.get(org.exist.security.SecurityManager.SYSTEM_USER);

            XQuery xquery = broker.getXQueryService();
            assertNotNull(xquery);
            Sequence seq = xquery.execute("//para[text:ngram-contains(., 'double match')]", null, AccessContext.TEST);
            assertNotNull(seq);
            assertEquals(1, seq.getItemCount());
            String result = queryResult2String(broker, seq);
            System.out.println("RESULT: " + result);
            XMLAssert.assertEquals("<para>" + MATCH_START + "double match" + MATCH_END + " " +
                MATCH_START + "double match" + MATCH_END + "</para>", result);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        } finally {
            pool.release(broker);
        }
    }

    @BeforeClass
    public static void startDB() {
        DBBroker broker = null;
        try {
            Configuration config = new Configuration();
            BrokerPool.configure(1, 5, config);
            pool = BrokerPool.getInstance();
        	assertNotNull(pool);
            broker = pool.get(org.exist.security.SecurityManager.SYSTEM_USER);
            assertNotNull(broker);
            TransactionManager transact = pool.getTransactionManager();
            assertNotNull(transact);
            Txn transaction = transact.beginTransaction();
            assertNotNull(transaction);
            System.out.println("Transaction started ...");

            Collection root = broker.getOrCreateCollection(transaction, TestConstants.TEST_COLLECTION_URI);
            assertNotNull(root);
            broker.saveCollection(transaction, root);

            transact.commit(transaction);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        } finally {
            if (pool != null)
                pool.release(broker);
        }
    }

    @AfterClass
    public static void closeDB() {
        BrokerPool pool = null;
        DBBroker broker = null;
        try {
            pool = BrokerPool.getInstance();
            assertNotNull(pool);
            broker = pool.get(org.exist.security.SecurityManager.SYSTEM_USER);
            assertNotNull(broker);
            TransactionManager transact = pool.getTransactionManager();
            assertNotNull(transact);
            Txn transaction = transact.beginTransaction();
            assertNotNull(transaction);
            System.out.println("Transaction started ...");

            Collection root = broker.getOrCreateCollection(transaction, TestConstants.TEST_COLLECTION_URI);
            assertNotNull(root);
            broker.removeCollection(transaction, root);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        } finally {
            if (pool != null) pool.release(broker);
        }
        BrokerPool.stopAll(false);
    }

    private void configureAndStore(String config) throws Exception {
        DBBroker broker = null;
        try {
            broker = pool.get(org.exist.security.SecurityManager.SYSTEM_USER);
            assertNotNull(broker);
            TransactionManager transact = pool.getTransactionManager();
            assertNotNull(transact);
            Txn transaction = transact.beginTransaction();
            assertNotNull(transaction);

            Collection root = broker.getOrCreateCollection(transaction, TestConstants.TEST_COLLECTION_URI);
            assertNotNull(root);
            CollectionConfigurationManager mgr = pool.getConfigurationManager();
            mgr.addConfiguration(transaction, broker, root, config);

            IndexInfo info = root.validateXMLResource(transaction, broker, XmldbURI.create("test_matches.xml"), XML);
            assertNotNull(info);
            root.store(transaction, broker, info, XML, false);
            
            transact.commit(transaction);
        } finally {
            pool.release(broker);
        }
    }

    private String queryResult2String(DBBroker broker, Sequence seq) throws SAXException, XPathException {
        Properties props = new Properties();
        props.setProperty(OutputKeys.INDENT, "no");
        props.setProperty(EXistOutputKeys.HIGHLIGHT_MATCHES, "elements");
        Serializer serializer = broker.getSerializer();
        serializer.reset();
        serializer.setProperties(props);
        return serializer.serialize((NodeProxy) seq.itemAt(0));
    }
}
