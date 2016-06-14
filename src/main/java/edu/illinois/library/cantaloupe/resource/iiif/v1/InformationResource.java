package edu.illinois.library.cantaloupe.resource.iiif.v1;

import java.io.FileNotFoundException;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.SerializationFeature;
import edu.illinois.library.cantaloupe.WebApplication;
import edu.illinois.library.cantaloupe.cache.Cache;
import edu.illinois.library.cantaloupe.cache.CacheFactory;
import edu.illinois.library.cantaloupe.config.Configuration;
import edu.illinois.library.cantaloupe.image.Format;
import edu.illinois.library.cantaloupe.image.Identifier;
import edu.illinois.library.cantaloupe.processor.Processor;
import edu.illinois.library.cantaloupe.processor.ProcessorFactory;
import edu.illinois.library.cantaloupe.resolver.Resolver;
import edu.illinois.library.cantaloupe.resolver.ResolverFactory;
import edu.illinois.library.cantaloupe.resource.SourceImageWrangler;
import org.restlet.data.CharacterSet;
import org.restlet.data.MediaType;
import org.restlet.data.Preference;
import org.restlet.data.Reference;
import org.restlet.ext.jackson.JacksonRepresentation;
import org.restlet.representation.EmptyRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import org.restlet.resource.ResourceException;

/**
 * Handles IIIF Image API 1.1 information requests.
 *
 * @see <a href="http://iiif.io/api/image/1.1/#image-info-request">Information
 * Requests</a>
 */
public class InformationResource extends Iiif1Resource {

    /**
     * Redirects /{identifier} to /{identifier}/info.json, respecting the
     * Servlet context root.
     */
    public static class RedirectingResource extends Iiif1Resource {
        @Get
        public Representation doGet() {
            final String identifier = (String) this.getRequest().
                    getAttributes().get("identifier");
            final Reference newRef = new Reference(
                    getPublicRootRef(getRequest()) +
                            WebApplication.IIIF_1_PATH + "/" + identifier +
                            "/info.json");
            redirectSeeOther(newRef);
            return new EmptyRepresentation();
        }
    }

    @Override
    protected void doInit() throws ResourceException {
        super.doInit();
        getResponseCacheDirectives().addAll(getCacheDirectives());
    }

    /**
     * Responds to information requests.
     *
     * @return JacksonRepresentation that will write an {@link ImageInfo}
     *         instance to JSON.
     * @throws Exception
     */
    @Get
    public Representation doGet() throws Exception {
        Map<String,Object> attrs = this.getRequest().getAttributes();
        Identifier identifier = new Identifier(
                Reference.decode((String) attrs.get("identifier")));
        identifier = decodeSlashes(identifier);

        // Get the resolver
        Resolver resolver = ResolverFactory.getResolver(identifier);
        Format format = Format.UNKNOWN;
        try {
            // Determine the format of the source image
            format = resolver.getSourceFormat();
        } catch (FileNotFoundException e) {
            if (Configuration.getInstance().
                    getBoolean(PURGE_MISSING_CONFIG_KEY, false)) {
                // if the image was not found, purge it from the cache
                final Cache cache = CacheFactory.getDerivativeCache();
                if (cache != null) {
                    cache.purgeImage(identifier);
                }
            }
            throw e;
        }

        // Obtain an instance of the processor assigned to that format in
        // the config file
        final Processor processor = ProcessorFactory.getProcessor(format);

        new SourceImageWrangler(resolver, processor, identifier).wrangle();

        // Get an ImageInfo instance corresponding to the source image
        ImageInfo imageInfo = ImageInfoFactory.newImageInfo(
                getImageUri(identifier), processor,
                getOrReadInfo(identifier, processor));

        getResponse().getHeaders().add("Link",
                String.format("<%s>;rel=\"profile\";", imageInfo.profile));

        JacksonRepresentation rep = new JacksonRepresentation<>(imageInfo);

        // If the client has requested JSON-LD, set the content type to
        // that; otherwise set it to JSON
        List<Preference<MediaType>> preferences = this.getRequest().
                getClientInfo().getAcceptedMediaTypes();
        if (preferences.get(0) != null && preferences.get(0).toString().
                startsWith("application/ld+json")) {
            rep.setMediaType(new MediaType("application/ld+json"));
        } else {
            rep.setMediaType(new MediaType("application/json"));
        }

        rep.getObjectWriter().
                without(SerializationFeature.WRITE_NULL_MAP_VALUES).
                without(SerializationFeature.WRITE_EMPTY_JSON_ARRAYS);
        rep.setCharacterSet(CharacterSet.UTF_8);
        return rep;
    }

    /**
     * @param identifier
     * @return Full image URI corresponding to the given identifier, respecting
     *         the X-Forwarded-* and X-IIIF-ID reverse proxy headers.
     */
    private String getImageUri(Identifier identifier) {
        final String identifierStr = getRequest().getHeaders().
                getFirstValue("X-IIIF-ID", true, identifier.toString());
        return getPublicRootRef(getRequest()) + WebApplication.IIIF_1_PATH +
                "/" + Reference.encode(identifierStr);
    }

}
