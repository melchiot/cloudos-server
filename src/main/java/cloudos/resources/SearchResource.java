package cloudos.resources;

import au.com.bytecode.opencsv.CSVWriter;
import cloudos.dao.AccountDAO;
import cloudos.dao.AccountGroupDAO;
import cloudos.dao.SessionDAO;
import cloudos.model.Account;
import cloudos.model.AccountGroup;
import cloudos.model.support.AccountGroupView;
import com.qmino.miredot.annotations.ReturnType;
import lombok.extern.slf4j.Slf4j;
import org.cobbzilla.util.reflect.ReflectionUtil;
import org.cobbzilla.wizard.dao.SearchResults;
import org.cobbzilla.wizard.ldap.LdapService;
import org.cobbzilla.wizard.model.ResultPage;
import org.cobbzilla.wizard.resources.ResourceUtil;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;

import static cloudos.resources.AccountGroupsResource.buildAccountGroupView;
import static cloudos.resources.DefaultSearchScrubber.DEFAULT_SEARCH_SCRUBBER;
import static org.cobbzilla.wizard.resources.ResourceUtil.ok;

@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Path(ApiConstants.SEARCH_ENDPOINT)
@Service @Slf4j
public class SearchResource {

    public static final String DATE_PREFIX = "DATETIME:";

    private static final String[] ACCOUNT_FIELDS
            = {"name", "firstName", "lastName", "admin", "suspended", "storageQuota",
                DATE_PREFIX+"ctime", DATE_PREFIX+"lastLogin",
                "email", "mobilePhone", "mobilePhoneCountryCode"};

    private static final String[] GROUP_FIELDS
            = {"name", "info.description", "info.storageQuota", "memberCount", DATE_PREFIX+"ctime" };

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormat.forPattern("YYYY-MM-dd HH:mm:ss");

    @Autowired private SessionDAO sessionDAO;
    @Autowired private AccountDAO accountDAO;
    @Autowired private AccountGroupDAO groupDAO;

    public enum Type {accounts, groups}

    /**
     * Download a CSV report. Must be admin
     * @param apiKey The session ID
     * @param type The type of report, either 'accounts' or 'groups'
     * @param page The page of results to return
     * @return The CSV report, with HTTP headers to force a download.
     * @statuscode 403 if caller is not an admin
     */
    @GET
    @Path("/{type}/download.csv")
    @Produces(MediaType.TEXT_PLAIN)
    @Consumes("*/*")
    @ReturnType("cloudos.resources.SearchResource.CsvOutput")
    public Response download(@HeaderParam(ApiConstants.H_API_KEY) String apiKey,
                             @PathParam("type") final String type,
                             @QueryParam("page") final ResultPage page) {

        final Account account = sessionDAO.find(apiKey);
        if (account == null) return ResourceUtil.notFound(apiKey);
        if (!account.isAdmin()) return ResourceUtil.forbidden();

        page.setPageNumber(1);
        page.setPageSize(Integer.MAX_VALUE);

        final StreamingOutput output = new CsvOutput(type, page, account);
        return Response.ok(output)
                .header("Content-Description", "File Transfer")
                .header("Content-Disposition", "attachment; filename="+type+"-"+page.hashCode()+".csv")
                .build();
    }

    private String[] csvFields(String type) {
        switch (Type.valueOf(type)) {
            case accounts: return ACCOUNT_FIELDS;
            case groups: return GROUP_FIELDS;
            default: throw new IllegalArgumentException("no csv fields defined for "+type);
        }
    }

    /**
     * Search CloudOs objects. If not admin, results will be scrubbed of any sensitive data.
     * @param apiKey The session ID
     * @param type The type of report, either 'accounts' or 'groups'
     * @param page The page of results to return
     * @return a SearchResults object containing the results
     */
    @POST
    @Path("/{type}")
    @ReturnType("org.cobbzilla.wizard.dao.SearchResults")
    public Response search(@HeaderParam(ApiConstants.H_API_KEY) String apiKey,
                           @PathParam("type") String type,
                           ResultPage page) {

        final Account account = sessionDAO.find(apiKey);
        if (account == null) return ResourceUtil.notFound(apiKey);

        return ok(search(type, page, account));
    }

    public SearchResults search(String type, ResultPage page, Account account) {
        if (!account.isAdmin()) page.setScrubber(DEFAULT_SEARCH_SCRUBBER);

        final SearchResults results;
        switch (Type.valueOf(type)) {
            case accounts:
                if (!page.hasBound(LdapService.BOUND_BASE)) page.setBound(LdapService.BOUND_BASE, accountDAO.parentDN());
                results = accountDAO.search(page);
                break;
            case groups:
                if (!page.hasBound(LdapService.BOUND_BASE)) page.setBound(LdapService.BOUND_BASE, groupDAO.parentDN());
                results = toGroupViews(groupDAO.search(page));
                break;
            default:
                throw new IllegalArgumentException("cannot search " + type);
        }
        return results;
    }

    private SearchResults<AccountGroupView> toGroupViews(SearchResults<AccountGroup> groups) {
        SearchResults<AccountGroupView> rval = new SearchResults<>();
        rval.setTotalCount(groups.getTotalCount());
        for (AccountGroup group : groups.getResults()) {
            rval.addResult(buildAccountGroupView(groupDAO, group, null));
        }
        return rval;
    }

    private class CsvOutput implements StreamingOutput {
        private final String type;
        private final ResultPage page;
        private final Account account;

        public CsvOutput(String type, ResultPage page, Account account) {
            this.type = type;
            this.page = page;
            this.account = account;
        }

        @Override
        public void write(OutputStream output) throws IOException, WebApplicationException {

            final CSVWriter writer = new CSVWriter(new OutputStreamWriter(output));
            final SearchResults results = search(type, page, account);

            final String[] fields = csvFields(type);
            writer.writeNext(fields); // header row

            for (Object result : results.getResults()) {
                final String[] line = new String[fields.length];
                for (int i=0; i<line.length; i++) {

                    String field = fields[i];
                    final boolean isDate = field.startsWith(DATE_PREFIX);
                    if (isDate) field = field.substring(DATE_PREFIX.length());

                    final Object rawValue;
                    try {
                        rawValue = ReflectionUtil.get(result, field);
                    } catch (Exception e) {
                        log.warn("Error getting field "+field+": "+e);
                        line[i] = null;
                        continue;
                    }
                    line[i] = (rawValue == null) ? null : (isDate ? DATE_FORMAT.print(((Number) rawValue).longValue()) : rawValue.toString());
                }
                writer.writeNext(line);
            }

            writer.close();
        }
    }
}