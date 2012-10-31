package ru.megaplan.jira.plugins.mpsrate.customfield;

import com.atlassian.crowd.embedded.api.User;
import com.atlassian.jira.imports.project.customfield.ProjectCustomFieldImporter;
import com.atlassian.jira.imports.project.customfield.ProjectImportableCustomField;
import com.atlassian.jira.imports.project.customfield.SelectCustomFieldImporter;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.context.IssueContext;
import com.atlassian.jira.issue.context.JiraContextNode;
import com.atlassian.jira.issue.customfields.CustomFieldType;
import com.atlassian.jira.issue.customfields.GroupSelectorField;
import com.atlassian.jira.issue.customfields.MultipleSettableCustomFieldType;
import com.atlassian.jira.issue.customfields.SortableCustomField;
import com.atlassian.jira.issue.customfields.config.item.SettableOptionsConfigItem;
import com.atlassian.jira.issue.customfields.impl.AbstractSingleFieldType;
import com.atlassian.jira.issue.customfields.impl.FieldValidationException;
import com.atlassian.jira.issue.customfields.manager.GenericConfigManager;
import com.atlassian.jira.issue.customfields.manager.OptionsManager;
import com.atlassian.jira.issue.customfields.option.Option;
import com.atlassian.jira.issue.customfields.option.OptionUtils;
import com.atlassian.jira.issue.customfields.option.Options;
import com.atlassian.jira.issue.customfields.persistence.CustomFieldValuePersister;
import com.atlassian.jira.issue.customfields.persistence.PersistenceFieldType;
import com.atlassian.jira.issue.customfields.statistics.SelectStatisticsMapper;
import com.atlassian.jira.issue.customfields.view.CustomFieldParams;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import com.atlassian.jira.issue.fields.config.FieldConfigItemType;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutItem;
import com.atlassian.jira.issue.fields.rest.FieldJsonRepresentation;
import com.atlassian.jira.issue.fields.rest.FieldTypeInfo;
import com.atlassian.jira.issue.fields.rest.FieldTypeInfoContext;
import com.atlassian.jira.issue.fields.rest.RestAwareCustomFieldType;
import com.atlassian.jira.issue.fields.rest.RestCustomFieldTypeOperations;
import com.atlassian.jira.issue.fields.rest.RestFieldOperationsHandler;
import com.atlassian.jira.issue.fields.rest.json.JsonData;
import com.atlassian.jira.issue.fields.rest.json.JsonType;
import com.atlassian.jira.issue.fields.rest.json.JsonTypeBuilder;
import com.atlassian.jira.issue.fields.rest.json.beans.CustomFieldOptionJsonBean;
import com.atlassian.jira.issue.fields.rest.json.beans.JiraBaseUrls;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.security.groups.GroupManager;
import com.atlassian.jira.user.util.UserManager;
import com.atlassian.jira.util.ErrorCollection;
import com.atlassian.jira.util.ErrorCollection.Reason;
import com.atlassian.jira.util.NotNull;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import ru.megaplan.jira.plugins.mpsrate.ao.RateService;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Select Custom Field Type allows selecting of a single {@link Option}.
 * <em>Transport Object</em> is {@link Option}
 *
 * dl>
 * <dt><strong>Transport Object Type</strong></dt>
 * <dd>{@link Option}</dd>
 * <dt><Strong>Database Storage Type</Strong></dt>
 * <dd>{@link String} of Option ID</dd>
 * </dl>
 */
public class RateCFType extends AbstractSingleFieldType<Option>
        implements MultipleSettableCustomFieldType<Option, Option>, SortableCustomField<String>, GroupSelectorField, ProjectImportableCustomField, RestAwareCustomFieldType, RestCustomFieldTypeOperations
{
    private final OptionsManager optionsManager;
    private final ProjectCustomFieldImporter projectCustomFieldImporter;
    private final JiraBaseUrls jiraBaseUrls;
    private final RateService rateService;
    private final JiraAuthenticationContext jiraAuthenticationContext;
    private final CustomFieldManager customFieldManager;
    private final UserManager userManager;
    private final GroupManager groupManager;

    private CustomField mpsLastWorkerCf;

    private static final Logger log = Logger.getLogger(RateCFType.class);

    public RateCFType(CustomFieldValuePersister customFieldValuePersister, OptionsManager optionsManager, GenericConfigManager genericConfigManager, JiraBaseUrls jiraBaseUrls, RateService rateService, JiraAuthenticationContext jiraAuthenticationContext, CustomFieldManager customFieldManager, UserManager userManager, GroupManager groupManager)
    {
        super(customFieldValuePersister, genericConfigManager);
        this.optionsManager = optionsManager;
        this.jiraBaseUrls = jiraBaseUrls;
        this.rateService = rateService;
        this.jiraAuthenticationContext = jiraAuthenticationContext;
        this.customFieldManager = customFieldManager;
        this.userManager = userManager;
        this.groupManager = groupManager;
        projectCustomFieldImporter = new SelectCustomFieldImporter();
       // mpsLastWorkerCf = customFieldManager.getCustomFieldObjectByName(MPSLASTWORKERCFNAME);
    }

    @Override
    public Set<Long> remove(final CustomField field)
    {
        final Set<Long> issues = super.remove(field);
        optionsManager.removeCustomFieldOptions(field);
        return issues;
    }

    @NotNull
    @Override
    protected PersistenceFieldType getDatabaseType()
    {
        return PersistenceFieldType.TYPE_LIMITED_TEXT;
    }

    @Override
    protected Object getDbValueFromObject(Option customFieldObject)
    {
        return null;
    }

    @Override
    protected Option getObjectFromDbValue(@NotNull Object databaseValue) throws FieldValidationException
    {
        return null;
    }

    /**
     * This default implementation will remove all values from the custom field for an issue. Since there can only be
     * one value for each CustomField instance, this implementation can safely ignore the objectValue
     *
     * @param option - ignored
     */
    @Override
    public void removeValue(final CustomField field, final Issue issue, final Option option)
    {
        updateValue(field, issue, null);
    }

    @Override
    public void createValue(CustomField field,
                     Issue issue,
                     Option value)  {
        updateValue(field, issue, value);
    }

    public static int denormalizeRating(int rating, int optionsSize) {
        return rating - optionsSize/2;
    }

    public static int normalizeRating(int rating, int optionsSize) {
        return rating + optionsSize/2;
    }

    @Override
    public void updateValue(com.atlassian.jira.issue.fields.CustomField customField, com.atlassian.jira.issue.Issue issue, Option value) {
        if (value == null) return;
        Long rating = value.getSequence();
        Options options = optionsManager.getOptions(customField.getRelevantConfig(issue));
        int resultRating = denormalizeRating(rating.intValue(), options.size());
        //if (resultRating >= 0) resultRating++;    // it is for exclude 0
        User worker = getWorkerFromIssue(issue);
        if (worker == null) return;
        rateService.addRating(issue.getKey(), jiraAuthenticationContext.getLoggedInUser().getName(), worker.getName(), resultRating, null);
    }

    private User getWorkerFromIssue(Issue issue) {
        String workerName = rateService.getWorker(issue.getKey());
        User worker = null;
        if (workerName != null) {
            worker = userManager.getUser(workerName);
        }
        if (worker == null) {
            worker = issue.getReporter();
            if (worker == null) {
                log.error("issue dont'have reporter : " + issue.getKey());
            }
        }
        return worker;
    }

        @Override
    public Option getSingularObjectFromString(final String string) throws FieldValidationException
    {
        if ("-1".equals(string))
        {
            return null;
        }
        return getOptionFromStringValue(string);
    }

    private Option getOptionFromStringValue(String selectValue)
            throws FieldValidationException
    {
        final Long aLong = OptionUtils.safeParseLong(selectValue);
        if (aLong != null)
        {
            final Option option = optionsManager.findByOptionId(aLong);
            if (option != null)
            {
                return option;
            }
            else
            {
                return null;
            }
        }
        else
        {
            return null;
        }
    }

    @Override
    public String getStringFromSingularObject(final Option optionObject)
    {
        if (optionObject == null)
        {
            return null;
        }

        return optionObject.getOptionId().toString();
    }

    public Set<Long> getIssueIdsWithValue(final CustomField field, final Option option)
    {
        if (option != null)
        {
            return customFieldValuePersister.getIssueIdsWithValue(field, PersistenceFieldType.TYPE_LIMITED_TEXT, option.getOptionId().toString());
        }
        else
        {
            return Collections.emptySet();
        }
    }

    @NotNull
    @Override
    public List<FieldConfigItemType> getConfigurationItemTypes()
    {
        final List<FieldConfigItemType> configurationItemTypes = super.getConfigurationItemTypes();
        configurationItemTypes.add(new SettableOptionsConfigItem(this, optionsManager));
        return configurationItemTypes;
    }

    @Override
    public void validateFromParams(final CustomFieldParams relevantParams, final ErrorCollection errorCollectionToAddTo, final FieldConfig config)
    {
        final String selectedString = (String) relevantParams.getFirstValueForNullKey();

        if (StringUtils.isNotBlank(selectedString) && !"-1".equals(selectedString))
        {
            // Test to see if the non blank value exists in the options
            final Options options = optionsManager.getOptions(config);
            final CustomField customField = config.getCustomField();
            final String validOptions = createValidOptionsString(options);
            Long optionId = null;
            try
            {
                optionId = Long.valueOf(selectedString);
            }
            catch (NumberFormatException e)
            {
                errorCollectionToAddTo.addError(customField.getId(), getI18nBean().getText("admin.errors.invalid.value.passed.for.customfield",
                        "'" + selectedString + "'", "'" + customField + "'", validOptions), Reason.VALIDATION_FAILED);
            }
            if ((options != null) && (options.getOptionById(optionId) == null))
            {

                errorCollectionToAddTo.addError(customField.getId(), getI18nBean().getText("admin.errors.invalid.value.passed.for.customfield",
                        "'" + selectedString + "'", "'" + customField + "'", validOptions), Reason.VALIDATION_FAILED);
            }
        }

    }

    public void setDefaultValue(final FieldConfig fieldConfig, final Option option)
    {
        Long id = null;
        if (option != null)
        {
            id = option.getOptionId();
        }
        genericConfigManager.update(CustomFieldType.DEFAULT_VALUE_TYPE, fieldConfig.getId().toString(), id);
    }

    public Option getDefaultValue(final FieldConfig fieldConfig)
    {
        Long optionId = (Long) genericConfigManager.retrieve(CustomFieldType.DEFAULT_VALUE_TYPE, fieldConfig.getId().toString());
        if (optionId == null)
        {
            return null;
        }
        return optionsManager.findByOptionId(optionId);
    }

    @Override
    @NotNull
    public Map<String, Object> getVelocityParameters(final Issue issue, final CustomField field, final FieldLayoutItem fieldLayoutItem)
    {
        Map<String, Object> result = super.getVelocityParameters(issue, field, fieldLayoutItem);
        User worker = getWorkerFromIssue(issue);
        String fieldDescription = fieldLayoutItem.getFieldDescription();
        if (worker != null)
            fieldDescription += "<br>Оценка будет выставлена пользователю : " + worker.getDisplayName();
        result.put("fieldDescription", fieldDescription);
        return result;
    }

    @Override
    public String getChangelogString(CustomField field, Option value)
    {
        return value == null ? null :  value.getValue();
    }

    private String createValidOptionsString(final Options options)
    {
        final List<Option> rootOptions = options.getRootOptions();
        final StringBuilder validOptions = new StringBuilder();

        for (Iterator<Option> optionIterator = rootOptions.iterator(); optionIterator.hasNext();)
        {
            Option option = optionIterator.next();
            validOptions.append(option.getOptionId()).append("[").append(option.getValue()).append("]");

            if (optionIterator.hasNext())
            {
                validOptions.append(", ");
            }
        }
        validOptions.append(", -1");
        return validOptions.toString();
    }

    //------------------------------------------------------------------------------------------- MultiSettable Methods
    public Options getOptions(final FieldConfig config, @Nullable final JiraContextNode jiraContextNode)
    {
        return optionsManager.getOptions(config);
    }

    // -------------------------------------------------------------------------------- Sortable custom field
    @Override
    public int compare(@NotNull final String customFieldObjectValue1, @NotNull final String customFieldObjectValue2, final FieldConfig fieldConfig)
    {
        final Options options = getOptions(fieldConfig, null);

        if (options != null)
        {
            final int v1 = options.indexOf(options.getOptionById(Long.valueOf(customFieldObjectValue1)));
            final int v2 = options.indexOf(options.getOptionById(Long.valueOf(customFieldObjectValue2)));

            if (v1 > v2)
            {
                return 1;
            }
            else if (v1 < v2)
            {
                return -1;
            }
            else
            {
                return 0;
            }

        }
        else
        {
            log.info("No options were found.");
            return 0;
        }
    }

    @Override
    public ProjectCustomFieldImporter getProjectImporter()
    {
        return projectCustomFieldImporter;
    }

    @Override
    public Object accept(VisitorBase visitor)
    {
        if (visitor instanceof Visitor)
        {
            return ((Visitor) visitor).visitSelect(this);
        }

        return super.accept(visitor);
    }

    public Query getQueryForGroup(final String fieldID, String groupName)
    {
        return new TermQuery(new Term(fieldID + SelectStatisticsMapper.RAW_VALUE_SUFFIX, groupName));
    }

    public interface Visitor<T> extends VisitorBase<T>
    {
        T visitSelect(RateCFType selectCustomFieldType);
    }

    @Override
    public FieldTypeInfo getFieldTypeInfo(FieldTypeInfoContext fieldTypeInfoContext)
    {
        // Get the allowed options
        FieldConfig config = ((CustomField) fieldTypeInfoContext.getOderableField()).getRelevantConfig(fieldTypeInfoContext.getIssueContext());
        Options options = optionsManager.getOptions(config);
        Collection<CustomFieldOptionJsonBean> optionBeans = CustomFieldOptionJsonBean.shortBeans(options, jiraBaseUrls);

        return new FieldTypeInfo(optionBeans, null);
    }

    @Override
    public JsonType getJsonSchema(CustomField customField)
    {
        return JsonTypeBuilder.custom(JsonType.STRING_TYPE, getKey(), customField.getIdAsLong());
    }

    @Override
    public FieldJsonRepresentation getJsonFromIssue(CustomField field, Issue issue, boolean renderedVersionRequested, @Nullable FieldLayoutItem fieldLayoutItem)
    {
        Option valueFromIssue = getValueFromIssue(field, issue);
        if (valueFromIssue == null)
        {
            return new FieldJsonRepresentation(new JsonData(null));
        }
        return new FieldJsonRepresentation(new JsonData(CustomFieldOptionJsonBean.shortBean(valueFromIssue, jiraBaseUrls)));
    }

    @Override
    public RestFieldOperationsHandler getRestFieldOperation(CustomField field)
    {
        return new SelectCustomFieldOperationsHandler(optionsManager, field, getI18nBean());
    }

    @Override
    public JsonData getJsonDefaultValue(IssueContext issueCtx, CustomField field)
    {
        FieldConfig config = field.getRelevantConfig(issueCtx);
        Option defaultValue = (Option) field.getCustomFieldType().getDefaultValue(config);
        return defaultValue == null ? null : new JsonData(CustomFieldOptionJsonBean.shortBean(defaultValue, jiraBaseUrls));
    }


}
