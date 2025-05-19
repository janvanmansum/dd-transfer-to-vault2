/*
 * Copyright (C) 2025 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.knaw.dans.transfer.core.oaiore;

import nl.knaw.dans.transfer.core.DveMetadata;
import nl.knaw.dans.transfer.core.oaiore.vocabulary.DansDataVaultMetadata;
import nl.knaw.dans.transfer.core.oaiore.vocabulary.DataverseCitationMetadata;
import nl.knaw.dans.transfer.core.oaiore.vocabulary.OaiOreMetadata;
import org.apache.commons.lang3.StringUtils;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sparql.vocabulary.FOAF;
import org.apache.jena.vocabulary.DCTerms;
import org.apache.jena.vocabulary.RDF;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;

public class OaiOreMetadataReader {

    public DveMetadata readMetadata(String json) {
        var model = ModelFactory.createDefaultModel();
        model.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), null, "JSON-LD");

        var builder = DveMetadata.builder();
        var aggregations = model.listStatements(null, RDF.type, OaiOreMetadata.Aggregation);

        if (aggregations.hasNext()) {
            var resource = aggregations.next().getSubject();

            builder.bagId(getRDFProperty(resource, DansDataVaultMetadata.dansBagId));
            builder.nbn(getRDFProperty(resource, DansDataVaultMetadata.dansNbn));
            builder.swordToken(getRDFProperty(resource, DansDataVaultMetadata.dansSwordToken));
            builder.dataSupplier(getRDFProperty(resource, DansDataVaultMetadata.dansDataSupplier));
            builder.dataversePid(getRDFProperty(resource, DansDataVaultMetadata.dansDataversePid));
            builder.dataversePidVersion(getRDFProperty(resource, DansDataVaultMetadata.dansDataversePidVersion));
            builder.otherId(getRDFProperty(resource, DansDataVaultMetadata.dansOtherId));
            builder.otherIdVersion(getRDFProperty(resource, DansDataVaultMetadata.dansOtherIdVersion));
            builder.title(getRDFProperty(resource, DCTerms.title));
            builder.metadata(json);
        }
        return builder.build();
    }

    private String getEmbeddedRDFProperty(Resource resource, Property parent, Property child) {
        var results = new HashSet<String>();

        resource.listProperties(parent)
            .forEachRemaining(item -> {
                var value = getRDFProperty(item.getObject().asResource(), child);

                if (value != null) {
                    results.add(value);
                }
            });

        if (results.isEmpty()) {
            return null;
        }

        // ensure we get deterministic results
        var list = new ArrayList<>(results);
        list.sort(String::compareTo);

        return StringUtils.join(list, "; ");
    }

    private String getRDFProperty(Resource resource, Property name) {
        var results = new HashSet<String>();

        resource.listProperties(name).forEachRemaining(item -> {
            if (item.getObject().isLiteral()) {
                results.add(item.getObject().asLiteral().getString());
            }
        });

        if (results.isEmpty()) {
            return null;
        }

        // ensure we get deterministic results
        var list = new ArrayList<>(results);
        list.sort(String::compareTo);

        return StringUtils.join(list, "; ");
    }

}
