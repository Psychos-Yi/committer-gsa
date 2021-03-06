/* Copyright 2013-2014 Norconex Inc.
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
package com.norconex.committer.gsa;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.http.HttpEntity;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import com.norconex.committer.core.AbstractMappedCommitter;
import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.ICommitOperation;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.commons.lang3.builder.ToStringBuilder;

/**
 * Commits documents to Google Search Appliance.
 * <p>
 * XML configuration usage:
 * </p>
 * 
 * <pre>
 *  &lt;committer class="com.norconex.committer.gsa.GsaCommitter"&gt;
 *      &lt;feedUrl&gt;(GSA feed URL)&lt;/feedUrl&gt;
 *      &lt;sourceReferenceField keep="[false|true]"&gt;
 *         (Optional name of field that contains the document reference, when 
 *         the default document reference is not used.  The reference value
 *         will be mapped to the "targetReferenceField" 
 *         specified or target repository default field if one is defined
 *         by the concrete implementation.
 *         Once re-mapped, this metadata source field is 
 *         deleted, unless "keep" is set to <code>true</code>.)
 *      &lt;/sourceReferenceField&gt;
 *      &lt;targetReferenceField&gt;
 *         (Name of target repository field where to store a document reference.
 *         If not specified, behavior is defined 
 *         by the concrete implementation.) 
 *      &lt;/targetReferenceField&gt;
 *      &lt;sourceContentField keep="[false|true]"&gt;
 *         (If you wish to use a metadata field to act as the document 
 *         "content", you can specify that field here.  Default 
 *         does not take a metadata field but rather the document content.
 *         Once re-mapped, the metadata source field is deleted,
 *         unless "keep" is set to <code>true</code>.)
 *      &lt;/sourceContentField&gt;
 *      &lt;targetContentField&gt;
 *         (Target repository field name for a document content/body.
 *          Default is defined by concrete implementation.)
 *      &lt;/targetContentField&gt;
 *      &lt;commitBatchSize&gt;
 *          (max number of documents to send to target repository at once)
 *      &lt;/commitBatchSize&gt;
 *      &lt;queueDir&gt;(optional path where to queue files)&lt;/queueDir&gt;
 *      &lt;queueSize&gt;(max queue size before committing)&lt;/queueSize&gt;
 *      &lt;maxRetries&gt;(max retries upon commit failures)&lt;/maxRetries&gt;
 *      &lt;maxRetryWait&gt;(max delay between retries)&lt;/maxRetryWait&gt;

 *  &lt;/committer&gt;
 * </pre>
 * 
 * @author Pascal Dimassimo
 */
public class GsaCommitter extends AbstractMappedCommitter {
    
    private static final Logger LOG = LogManager.getLogger(GsaCommitter.class);

    private final CloseableHttpClient httpclient;
    
    private String feedUrl;

    public GsaCommitter() {
        super();
        httpclient = HttpClients.createDefault();
    }

    public String getFeedUrl() {
        return feedUrl;
    }

    public void setFeedUrl(String feedUrl) {
        this.feedUrl = feedUrl;
    }

    @Override
    protected void commitBatch(List<ICommitOperation> batch) {

        File xmlFile = null;
        try {
            xmlFile = File.createTempFile("batch", ".xml");
            FileOutputStream fout = new FileOutputStream(xmlFile);
            XmlOutput xmlOutput = new XmlOutput(fout);
            Map<String, Integer> stats = xmlOutput.write(batch);
            fout.close();
            
            HttpPost post = new HttpPost(feedUrl);
            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            builder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);
            builder.addBinaryBody("data", xmlFile, 
                    ContentType.APPLICATION_XML, xmlFile.getName());
            builder.addTextBody("datasource", "GSA_Commiter");
            builder.addTextBody("feedtype", "full");
            
            HttpEntity entity = builder.build();
            post.setEntity(entity);
            CloseableHttpResponse response = httpclient.execute(post);
            StatusLine status = response.getStatusLine();
            if (status.getStatusCode() != 200) {
                throw new CommitterException(
                        "Invalid response to Committer HTTP request. "
                      + "Response code: " + status.getStatusCode()
                      + ". Response Message: " + status.getReasonPhrase());
            }
                LOG.info("Sent " + stats.get("docAdded") + " additions and " 
            + stats.get("docRemoved") + " removals to GSA");
            
        } catch (Exception e) {
            throw new CommitterException(
                    "Cannot index document batch to GSA.", e);
        } finally {
            FileUtils.deleteQuietly(xmlFile);
        }
    }
    
    @Override
    protected void loadFromXml(XMLConfiguration xml) {
        setFeedUrl(xml.getString("feedUrl", null));
    }

    @Override
    protected void saveToXML(XMLStreamWriter writer) throws XMLStreamException {
        writer.writeStartElement("feedUrl");
        writer.writeCharacters(getFeedUrl());
        writer.writeEndElement();
    }

    @Override
    public boolean equals(final Object other) {
        if (!(other instanceof GsaCommitter))
            return false;
        GsaCommitter castOther = (GsaCommitter) other;
        return new EqualsBuilder().appendSuper(super.equals(other))
                .append(feedUrl, castOther.feedUrl).isEquals();
    }
    @Override
    public int hashCode() {
        return new HashCodeBuilder().appendSuper(super.hashCode())
                .append(feedUrl).toHashCode();
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)
                .appendSuper(super.toString()).append("feedUrl", feedUrl)
                .toString();
    }
    
    
}
