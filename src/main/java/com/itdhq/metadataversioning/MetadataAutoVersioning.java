package com.itdhq.metadataversioning;

/**
 * Created by malchun on 11/25/15.
 */

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.*;

import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.content.ContentServicePolicies;
import org.alfresco.repo.copy.CopyBehaviourCallback;
import org.alfresco.repo.copy.CopyDetails;
import org.alfresco.repo.copy.CopyServicePolicies;
import org.alfresco.repo.copy.DefaultCopyBehaviourCallback;
import org.alfresco.repo.dictionary.DictionaryDAO;
import org.alfresco.repo.dictionary.DictionaryListener;
import org.alfresco.repo.lock.LockUtils;
import org.alfresco.repo.node.NodeServicePolicies;
import org.alfresco.repo.node.NodeServicePolicies.OnUpdatePropertiesPolicy;
import org.alfresco.repo.policy.Behaviour;
import org.alfresco.repo.policy.JavaBehaviour;
import org.alfresco.repo.policy.PolicyComponent;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.security.authentication.AuthenticationUtil.RunAsWork;
import org.alfresco.repo.transaction.AlfrescoTransactionSupport;
import org.alfresco.repo.version.VersionModel;
import org.alfresco.repo.version.VersionServicePolicies;
import org.alfresco.service.cmr.lock.LockService;
import org.alfresco.service.cmr.repository.AssociationRef;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.version.Version;
import org.alfresco.service.cmr.version.VersionHistory;
import org.alfresco.service.cmr.version.VersionService;
import org.alfresco.service.cmr.version.VersionType;
import org.alfresco.service.namespace.NamespacePrefixResolver;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.alfresco.util.EqualsHelper;
import org.springframework.extensions.surf.util.I18NUtil;
import org.apache.log4j.Logger;
import org.springframework.util.Assert;


public class MetadataAutoVersioning
    implements ContentServicePolicies.OnContentUpdatePolicy,
        NodeServicePolicies.BeforeAddAspectPolicy,
        NodeServicePolicies.OnAddAspectPolicy,
        NodeServicePolicies.OnRemoveAspectPolicy,
        NodeServicePolicies.OnDeleteNodePolicy,
        NodeServicePolicies.OnUpdatePropertiesPolicy,
        NodeServicePolicies.OnCreateAssociationPolicy,
        NodeServicePolicies.OnDeleteAssociationPolicy,
        NodeServicePolicies.OnCreateChildAssociationPolicy,
        NodeServicePolicies.OnDeleteChildAssociationPolicy,
        VersionServicePolicies.AfterCreateVersionPolicy,
        CopyServicePolicies.OnCopyNodePolicy,
        DictionaryListener
{
    private Logger logger = Logger.getLogger(MetadataAutoVersioning.class);

    /** The i18n'ized messages */
    private static final String MSG_INITIAL_VERSION = "create_version.initial_version";
    private static final String MSG_AUTO_VERSION = "create_version.auto_version";
    private static final String MSG_AUTO_VERSION_PROPS = "create_version.auto_version_props";

    /** Transaction resource key */
    private static final String KEY_VERSIONED_NODEREFS = "versioned_noderefs";

    private PolicyComponent policyComponent;
    private NodeService nodeService;
    private LockService lockService;
    private VersionService versionService;
    private DictionaryDAO dictionaryDAO;
    private NamespacePrefixResolver namespacePrefixResolver;
    private boolean enableAutoVersioning;
    private boolean customAutoVersioning;
    private boolean autoVersionAssocs;
    private boolean autoVersionChildAssocs;
    private long autoAssociationDelay;
    JavaBehaviour onUpdatePropertiesBehaviour;

    /**
     * Optional list of excluded props
     * - only applies if cm:autoVersionOnUpdateProps=true (and cm:autoVersion=true)
     * - if any one these props changes then "auto version on prop update" does not occur (even if there are other property changes)
     */
    private List<String> excludedOnUpdateProps = Collections.emptyList();
    private List<String> excludedOnUpdateAssocs = Collections.emptyList();
    private List<String> excludedOnUpdateChildAssocs = Collections.emptyList();

    private Set<QName> excludedOnUpdatePropQNames = Collections.emptySet();
    private Set<QName> excludedOnUpdateAssocsQNames = Collections.emptySet();
    private Set<QName> excludedOnUpdateChildAssocsQNames = Collections.emptySet();

    public void setPolicyComponent(PolicyComponent policyComponent) { this.policyComponent = policyComponent; }
    public void setVersionService(VersionService versionService) { this.versionService = versionService; }
    public void setNodeService(NodeService nodeService) { this.nodeService = nodeService; }
    public void setLockService(LockService lockService) { this.lockService = lockService; }
    public void setDictionaryDAO(DictionaryDAO dictionaryDAO) { this.dictionaryDAO = dictionaryDAO; }
    public void setNamespacePrefixResolver(NamespacePrefixResolver namespacePrefixResolver) { this.namespacePrefixResolver = namespacePrefixResolver; }
    public void setEnableAutoVersioning(boolean enableAutoVersioning) { this.enableAutoVersioning = enableAutoVersioning; }
    public void setCustomAutoVersioning(boolean customAutoVersioning) { this.customAutoVersioning = customAutoVersioning; }
    public void setAutoVersionAssocs(boolean autoVersionAssocs) { this.autoVersionAssocs = autoVersionAssocs; }
    public void setAutoVersionChildAssocs(boolean autoVersionChildAssocs) { this.autoVersionChildAssocs = autoVersionChildAssocs; }
    public void setAutoAssociationDelay(long autoAssociationDelay) { this.autoAssociationDelay = autoAssociationDelay; }
    public List<String> getExcludedOnUpdateProps() { return excludedOnUpdateProps; }

    public void setExcludedOnUpdateProps(List<String> excludedOnUpdateProps)
    {
        this.excludedOnUpdateProps = Collections.unmodifiableList(excludedOnUpdateProps);
        logger.info("Excluded aspects : " + this.excludedOnUpdateProps.toString());
    }

    public List<String> getExcludedOnUpdateAssocs() { return excludedOnUpdateAssocs; }

    public void setExcludedOnUpdateAssocs(List<String> excludedOnUpdateAssocs)
    {
        this.excludedOnUpdateAssocs = Collections.unmodifiableList(excludedOnUpdateAssocs);
        logger.info("Excluded aspects : " + this.excludedOnUpdateAssocs.toString());
    }

    public List<String> getExcludedOnUpdateChildAssocs() { return excludedOnUpdateChildAssocs; }

    public void setExcludedOnUpdateChildAssocs(List<String> excludedOnUpdateChildAssocs)
    {
        this.excludedOnUpdateChildAssocs = Collections.unmodifiableList(excludedOnUpdateChildAssocs);
        logger.info("Excluded aspects : " + this.excludedOnUpdateChildAssocs.toString());
    }

    /**
     * Initialise the versionable aspect policies
     */
    public void init()
    {
        if (!enableAutoVersioning) {
            logger.info("I'm MetadataAutoVersioning extension and i won't work because 'enableAutoVersioning' property set to false!");
            return;
        }
        logger.info("I'm MetadataAutoVersioning extension!");
        this.policyComponent.bindClassBehaviour(
                QName.createQName(NamespaceService.ALFRESCO_URI, "beforeAddAspect"),
                ContentModel.ASPECT_VERSIONABLE,
                new JavaBehaviour(this, "beforeAddAspect", Behaviour.NotificationFrequency.EVERY_EVENT));

        this.policyComponent.bindClassBehaviour(
                QName.createQName(NamespaceService.ALFRESCO_URI, "onAddAspect"),
                ContentModel.ASPECT_VERSIONABLE,
                new JavaBehaviour(this, "onAddAspect", Behaviour.NotificationFrequency.TRANSACTION_COMMIT));

        this.policyComponent.bindClassBehaviour(
                QName.createQName(NamespaceService.ALFRESCO_URI, "onRemoveAspect"),
                ContentModel.ASPECT_VERSIONABLE,
                new JavaBehaviour(this, "onRemoveAspect", Behaviour.NotificationFrequency.TRANSACTION_COMMIT));

        this.policyComponent.bindClassBehaviour(
                QName.createQName(NamespaceService.ALFRESCO_URI, "onDeleteNode"),
                ContentModel.ASPECT_VERSIONABLE,
                new JavaBehaviour(this, "onDeleteNode", Behaviour.NotificationFrequency.TRANSACTION_COMMIT));

        this.policyComponent.bindClassBehaviour(
                QName.createQName(NamespaceService.ALFRESCO_URI, "afterCreateVersion"),
                ContentModel.ASPECT_VERSIONABLE,
                new JavaBehaviour(this, "afterCreateVersion", Behaviour.NotificationFrequency.EVERY_EVENT));

        this.policyComponent.bindClassBehaviour(
                ContentServicePolicies.OnContentUpdatePolicy.QNAME,
                ContentModel.ASPECT_VERSIONABLE,
                new JavaBehaviour(this, "onContentUpdate", Behaviour.NotificationFrequency.TRANSACTION_COMMIT));

        this.policyComponent.bindAssociationBehaviour(
                NodeServicePolicies.OnCreateAssociationPolicy.QNAME,
                ContentModel.ASPECT_VERSIONABLE,
                new JavaBehaviour(this, "onCreateAssociation", Behaviour.NotificationFrequency.TRANSACTION_COMMIT));

        this.policyComponent.bindAssociationBehaviour(
                NodeServicePolicies.OnDeleteAssociationPolicy.QNAME,
                ContentModel.ASPECT_VERSIONABLE,
                new JavaBehaviour(this, "onDeleteAssociation", Behaviour.NotificationFrequency.TRANSACTION_COMMIT));

        this.policyComponent.bindAssociationBehaviour(
                NodeServicePolicies.OnCreateChildAssociationPolicy.QNAME,
                ContentModel.ASPECT_VERSIONABLE,
                new JavaBehaviour(this, "onCreateChildAssociation", Behaviour.NotificationFrequency.TRANSACTION_COMMIT));

        this.policyComponent.bindAssociationBehaviour(
                NodeServicePolicies.OnDeleteChildAssociationPolicy.QNAME,
                ContentModel.ASPECT_VERSIONABLE,
                new JavaBehaviour(this, "onDeleteChildAssociation", Behaviour.NotificationFrequency.TRANSACTION_COMMIT));

        onUpdatePropertiesBehaviour = new JavaBehaviour(this, "onUpdateProperties", Behaviour.NotificationFrequency.TRANSACTION_COMMIT);
        this.policyComponent.bindClassBehaviour(
                OnUpdatePropertiesPolicy.QNAME,
                ContentModel.ASPECT_VERSIONABLE,
                onUpdatePropertiesBehaviour);

        this.policyComponent.bindClassBehaviour(
                QName.createQName(NamespaceService.ALFRESCO_URI, "getCopyCallback"),
                ContentModel.ASPECT_VERSIONABLE,
                new JavaBehaviour(this, "getCopyCallback"));

        this.dictionaryDAO.registerListener(this);
    }

    /**
     * @see org.alfresco.repo.node.NodeServicePolicies.OnDeleteNodePolicy#onDeleteNode(org.alfresco.service.cmr.repository.ChildAssociationRef, boolean)
     */
    public void onDeleteNode(ChildAssociationRef childAssocRef, boolean isNodeArchived)
    {
        if (isNodeArchived == false) {
            // If we are perminantly deleting the node then we need to remove the associated version history
            this.versionService.deleteVersionHistory(childAssocRef.getChildRef());
        }
        // otherwise we do nothing since we need to hold onto the version history in case the node is restored later
    }

    /**
     * @return          Returns the CopyBehaviourCallback
     */
    public CopyBehaviourCallback getCopyCallback(QName classRef, CopyDetails copyDetails)
    {
        return VersionableAspectCopyBehaviourCallback.INSTANCE;
    }

    @Override
    public void onCreateAssociation(AssociationRef associationRef) {
        // TODO
        NodeRef sourceAssocNode = associationRef.getSourceRef();
        if ((this.nodeService.exists(sourceAssocNode) == true) &&
                !LockUtils.isLockedAndReadOnly(sourceAssocNode, lockService) &&
                (this.nodeService.hasAspect(sourceAssocNode, ContentModel.ASPECT_VERSIONABLE) == true) &&
                (this.nodeService.hasAspect(sourceAssocNode, ContentModel.ASPECT_TEMPORARY) == false)) {
            boolean autoVersion = false;
            Boolean value = (Boolean) this.nodeService.getProperty(sourceAssocNode, ContentModel.PROP_AUTO_VERSION);
            if (value != null) {
                // If the value is not null then
                autoVersion = value.booleanValue();
            }
            // To here

            if ((true == autoVersionAssocs) && (true == autoVersion) && (true == customAutoVersioning)
                    && (!excludedOnUpdateAssocsQNames.contains(associationRef.getTypeQName()))) {
                associationAutoVersioning(sourceAssocNode);
            }
        } else {
            throw new AlfrescoRuntimeException("Can't find source Node");
        }
    }

    @Override
    public void onDeleteAssociation(AssociationRef associationRef) {
        // TODO
        NodeRef sourceAssocNode = associationRef.getSourceRef();
        if ((this.nodeService.exists(sourceAssocNode) == true) &&
                !LockUtils.isLockedAndReadOnly(sourceAssocNode, lockService) &&
                (this.nodeService.hasAspect(sourceAssocNode, ContentModel.ASPECT_VERSIONABLE) == true) &&
                (this.nodeService.hasAspect(sourceAssocNode, ContentModel.ASPECT_TEMPORARY) == false)) {
            boolean autoVersion = false;
            Boolean value = (Boolean) this.nodeService.getProperty(sourceAssocNode, ContentModel.PROP_AUTO_VERSION);
            if (value != null) {
                // If the value is not null then
                autoVersion = value.booleanValue();
            }
            // To here

            if ((true == autoVersionAssocs) && (true == autoVersion) && (true == customAutoVersioning)
                    && (!excludedOnUpdateAssocsQNames.contains(associationRef.getTypeQName()))) {
                associationAutoVersioning(sourceAssocNode);
            }
        } else {
            throw new AlfrescoRuntimeException("Can't find source Node");
        }
    }

    @Override
    public void onCreateChildAssociation(ChildAssociationRef childAssociationRef, boolean b) {
        // TODO
        NodeRef parentAssocNode = childAssociationRef.getParentRef();
        if ((this.nodeService.exists(parentAssocNode) == true) &&
                !LockUtils.isLockedAndReadOnly(parentAssocNode, lockService) &&
                (this.nodeService.hasAspect(parentAssocNode, ContentModel.ASPECT_VERSIONABLE) == true) &&
                (this.nodeService.hasAspect(parentAssocNode, ContentModel.ASPECT_TEMPORARY) == false)) {
            boolean autoVersion = false;
            Boolean value = (Boolean) this.nodeService.getProperty(parentAssocNode, ContentModel.PROP_AUTO_VERSION);
            if (value != null) {
                // If the value is not null then
                autoVersion = value.booleanValue();
            }
            // To here

            if ((true == autoVersionChildAssocs) && (true == autoVersion) && (true == customAutoVersioning)
                    && (!excludedOnUpdateAssocsQNames.contains(childAssociationRef.getTypeQName()))) {
                associationAutoVersioning(parentAssocNode);
            }
        } else {
            throw new AlfrescoRuntimeException("Can't find parent Node");
        }
    }

    @Override
    public void onDeleteChildAssociation(ChildAssociationRef childAssociationRef) {
        // TODO
        NodeRef parentAssocNode = childAssociationRef.getParentRef();
        if ((this.nodeService.exists(parentAssocNode) == true) &&
                !LockUtils.isLockedAndReadOnly(parentAssocNode, lockService) &&
                (this.nodeService.hasAspect(parentAssocNode, ContentModel.ASPECT_VERSIONABLE) == true) &&
                (this.nodeService.hasAspect(parentAssocNode, ContentModel.ASPECT_TEMPORARY) == false)) {
            boolean autoVersion = false;
            Boolean value = (Boolean) this.nodeService.getProperty(parentAssocNode, ContentModel.PROP_AUTO_VERSION);
            if (value != null) {
                // If the value is not null then
                autoVersion = value.booleanValue();
            }
            // To here

            if ((true == autoVersionChildAssocs) && (true == autoVersion) && (true == customAutoVersioning)
                    && (!excludedOnUpdateAssocsQNames.contains(childAssociationRef.getTypeQName()))) {
                associationAutoVersioning(parentAssocNode);
            }
        } else {
            throw new AlfrescoRuntimeException("Can't find parent Node");
        }
    }

    private void associationAutoVersioning(NodeRef assocNode)
    {
        VersionHistory versionHistory = versionService.getVersionHistory(assocNode);
        Version lastVersion = versionHistory.getHeadVersion();
        logger.info("Catch it!");
        Date now = new Date();
        Date versionCreated = (Date) lastVersion.getVersionProperty("created");
        long diffSecs = (now.getTime() - versionCreated.getTime()) / 1000 % 60;
        logger.info(now.toString() +  " \ntype :" + now.getClass().toString());
        logger.info(lastVersion.getVersionProperty("created").toString() + " \ntype :" + lastVersion.getVersionProperty("created").getClass().toString());
        if (diffSecs > autoAssociationDelay) {
            logger.info("Creating assoc version");
            // Create the auto-version
            Map<String, Serializable> versionProperties = new HashMap<String, Serializable>(4);
            versionProperties.put(Version.PROP_DESCRIPTION, I18NUtil.getMessage(MSG_AUTO_VERSION_PROPS));
            versionProperties.put(VersionModel.PROP_VERSION_TYPE, VersionType.MINOR);

            createVersionImpl(assocNode, versionProperties);
        }
    }

    /**
     * Copy behaviour for the <b>cm:versionable</b> aspect
     *
     * @author Derek Hulley
     * @since 3.2
     */
    private static class VersionableAspectCopyBehaviourCallback extends DefaultCopyBehaviourCallback
    {
        private static final CopyBehaviourCallback INSTANCE = new VersionableAspectCopyBehaviourCallback();

        /**
         * Copy the aspect, but only the {@link ContentModel#PROP_AUTO_VERSION} and {@link ContentModel#PROP_AUTO_VERSION_PROPS} properties
         */
        @Override
        public Map<QName, Serializable> getCopyProperties(
                QName classQName,
                CopyDetails copyDetails,
                Map<QName, Serializable> properties)
        {
            Serializable value1 = properties.get(ContentModel.PROP_AUTO_VERSION);
            Serializable value2 = properties.get(ContentModel.PROP_AUTO_VERSION_PROPS);

            if ((value1 != null) || (value2 != null))
            {
                Map<QName, Serializable> newProperties = new HashMap<QName, Serializable>(2);

                if (value1 != null)
                {
                    newProperties.put(ContentModel.PROP_AUTO_VERSION, value1);
                }

                if (value2 != null)
                {
                    newProperties.put(ContentModel.PROP_AUTO_VERSION_PROPS, value2);
                }

                return newProperties;
            }
            else
            {
                return Collections.emptyMap();
            }
        }
    }

    /**
     * Before add aspect policy behaviour
     *
     * @param nodeRef NodeRef
     * @param aspectTypeQName QName
     */
    public void beforeAddAspect(final NodeRef nodeRef, QName aspectTypeQName)
    {
        AuthenticationUtil.runAsSystem(new RunAsWork<Void>()
        {
            @Override
            public Void doWork() throws Exception
            {
                if (nodeService.hasAspect(nodeRef, ContentModel.ASPECT_VERSIONABLE) == false
                        && versionService.getVersionHistory(nodeRef) != null)
                {
                    versionService.deleteVersionHistory(nodeRef);
                    logger.warn("The version history of node " + nodeRef
                            + " that doesn't have versionable aspect was deleted");
                }
                return null;
            }
        });
    }

    /**
     * On add aspect policy behaviour
     *
     * @param nodeRef NodeRef
     * @param aspectTypeQName QName
     */
    public void onAddAspect(NodeRef nodeRef, QName aspectTypeQName)
    {
        if (this.nodeService.exists(nodeRef) == true
                && this.nodeService.hasAspect(nodeRef, ContentModel.ASPECT_VERSIONABLE) == true
                && aspectTypeQName.equals(ContentModel.ASPECT_VERSIONABLE) == true)
        {
            boolean initialVersion = true;
            Boolean value = (Boolean)this.nodeService.getProperty(nodeRef, ContentModel.PROP_INITIAL_VERSION);
            if (value != null)
            {
                initialVersion = value.booleanValue();
            }
            // else this means that the default value has not been set the versionable aspect we applied pre-1.2

            if (initialVersion == true)
            {
                @SuppressWarnings("unchecked")
                Map<NodeRef, NodeRef> versionedNodeRefs = (Map<NodeRef, NodeRef>) AlfrescoTransactionSupport.getResource(KEY_VERSIONED_NODEREFS);
                if (versionedNodeRefs == null || versionedNodeRefs.containsKey(nodeRef) == false)
                {
                    // Create the initial-version
                    Map<String, Serializable> versionProperties = new HashMap<String, Serializable>(1);

                    // If a major version is requested, indicate it in the versionProperties map
                    String versionType = (String) nodeService.getProperty(nodeRef, ContentModel.PROP_VERSION_TYPE);
                    if (versionType == null  || !versionType.equals(VersionType.MINOR.toString()))
                    {
                        versionProperties.put(VersionModel.PROP_VERSION_TYPE, VersionType.MAJOR);
                    }

                    versionProperties.put(Version.PROP_DESCRIPTION, I18NUtil.getMessage(MSG_INITIAL_VERSION));

                    createVersionImpl(nodeRef, versionProperties);
                }
            }
        }
    }

    /**
     * @see org.alfresco.repo.node.NodeServicePolicies.OnRemoveAspectPolicy#onRemoveAspect(org.alfresco.service.cmr.repository.NodeRef, org.alfresco.service.namespace.QName)
     */
    public void onRemoveAspect(NodeRef nodeRef, QName aspectTypeQName)
    {
        // When the versionable aspect is removed from a node, then delete the associated version history
        this.versionService.deleteVersionHistory(nodeRef);
    }

    /**
     * On content update policy behaviour
     *
     * If applicable and "cm:autoVersion" is TRUE then version the node on content update (even if no property updates)
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void onContentUpdate(NodeRef nodeRef, boolean newContent)
    {
        if (this.nodeService.exists(nodeRef) == true &&
                this.nodeService.hasAspect(nodeRef, ContentModel.ASPECT_VERSIONABLE) == true &&
                this.nodeService.hasAspect(nodeRef, ContentModel.ASPECT_TEMPORARY) == false)
        {
            Map<NodeRef, NodeRef> versionedNodeRefs = (Map)AlfrescoTransactionSupport.getResource(KEY_VERSIONED_NODEREFS);
            if (versionedNodeRefs == null || versionedNodeRefs.containsKey(nodeRef) == false)
            {
                // Determine whether the node is auto versionable (for content updates) or not
                boolean autoVersion = false;
                Boolean value = (Boolean)this.nodeService.getProperty(nodeRef, ContentModel.PROP_AUTO_VERSION);
                if (value != null)
                {
                    // If the value is not null then
                    autoVersion = value.booleanValue();
                }
                // else this means that the default value has not been set and the versionable aspect was applied pre-1.1

                if (autoVersion == true)
                {
                    // Create the auto-version
                    Map<String, Serializable> versionProperties = new HashMap<String, Serializable>(1);
                    versionProperties.put(Version.PROP_DESCRIPTION, I18NUtil.getMessage(MSG_AUTO_VERSION));
                    // FUCK! What is it?
                    versionProperties.put(VersionModel.PROP_VERSION_TYPE, VersionType.MINOR);

                    createVersionImpl(nodeRef, versionProperties);
                }
            }
        }
    }

    /**
     * On update properties policy behaviour
     *
     * If applicable and "cm:autoVersionOnUpdateProps" is TRUE then version the node on properties update (even if no content updates)
     *
     * @since 3.2
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void onUpdateProperties(
            NodeRef nodeRef,
            Map<QName, Serializable> before,
            Map<QName, Serializable> after)
    {
        if ((this.nodeService.exists(nodeRef) == true) &&
                !LockUtils.isLockedAndReadOnly(nodeRef, lockService) &&
                (this.nodeService.hasAspect(nodeRef, ContentModel.ASPECT_VERSIONABLE) == true) &&
                (this.nodeService.hasAspect(nodeRef, ContentModel.ASPECT_TEMPORARY) == false))
        {
            logger.info("Updating properties...");
            onUpdatePropertiesBehaviour.disable();
            try
            {
                Map<NodeRef, NodeRef> versionedNodeRefs = (Map)AlfrescoTransactionSupport.getResource(KEY_VERSIONED_NODEREFS);
                if (versionedNodeRefs == null || versionedNodeRefs.containsKey(nodeRef) == false)
                {
                    // Determine whether the node is auto versionable (for property only updates) or not
                    // From here its mine (for symmetry)
                    boolean autoVersion = false;
                    Boolean value = (Boolean)this.nodeService.getProperty(nodeRef, ContentModel.PROP_AUTO_VERSION);
                    if (value != null)
                    {
                        // If the value is not null then
                        autoVersion = value.booleanValue();
                    }
                    // To here
                    boolean autoVersionProps = false;
                    value = (Boolean)this.nodeService.getProperty(nodeRef, ContentModel.PROP_AUTO_VERSION_PROPS);
                    if (value != null)
                    {
                        // If the value is not null then
                        autoVersionProps = value.booleanValue();
                    }

                    // TODO Not perfect - old politics could autoversion only props
                    if ((autoVersionProps == true) && (autoVersion == true))
                    {
                        logger.info("OK, at least i'm trying!");
                        // logger.info("Before : " + before.toString());
                        // logger.info("After : " + after.toString());
                        // Check for explicitly excluded props - if one or more excluded props changes then do not auto-version on this event (even if other props changed)
                        if (excludedOnUpdatePropQNames.size() > 0)
                        {
                            // From here its mine
                            if (customAutoVersioning) {
                                if (findDiffProps(before, after) == 0) {
                                    return ;
                                }
                            } else {
                                Set<QName> propNames = new HashSet<>();
                                propNames.addAll(after.keySet());
                                propNames.addAll(before.keySet());
                                propNames.retainAll(excludedOnUpdatePropQNames);

                                if (propNames.size() > 0) {
                                    for (QName prop : propNames) {
                                        Serializable beforeValue = before.get(prop);
                                        Serializable afterValue = after.get(prop);

                                        if (EqualsHelper.nullSafeEquals(beforeValue, afterValue) != true) {
                                            return;
                                        }
                                    }
                                }
                            }
                            // To here
                            // drop through and auto-version
                        }

                        // Create the auto-version
                        Map<String, Serializable> versionProperties = new HashMap<String, Serializable>(4);
                        versionProperties.put(Version.PROP_DESCRIPTION, I18NUtil.getMessage(MSG_AUTO_VERSION_PROPS));
                        versionProperties.put(VersionModel.PROP_VERSION_TYPE, VersionType.MINOR);

                        createVersionImpl(nodeRef, versionProperties);
                    }
                }
            }
            finally
            {
                onUpdatePropertiesBehaviour.enable();
            }
        }
    }

    // From here its mine
    private int findDiffProps(Map<QName, Serializable> before, Map<QName, Serializable> after)
    {

        Set<QName> propNames = new HashSet<>();
        propNames.addAll(after.keySet());
        propNames.addAll(before.keySet());
        propNames.removeAll(excludedOnUpdatePropQNames);

        int diffCount = 0;
        for (QName prop : propNames)
        {
            Serializable beforeValue = before.get(prop);
            Serializable afterValue = after.get(prop);

            if (EqualsHelper.nullSafeEquals(beforeValue, afterValue) != true)
            {
                ++diffCount;
            }
        }
        return diffCount;
    }

    // To here
    /**
     * On create version implementation method
     *
     * @param nodeRef NodeRef
     * @param versionProperties Map<String, Serializable>
     */
    private void createVersionImpl(NodeRef nodeRef, Map<String, Serializable> versionProperties)
    {
        final VersionService vs = this.versionService;
        final NodeRef nf = nodeRef;
        final Map<String, Serializable> vp = versionProperties;

        // From here is mine
        if (customAutoVersioning) {
            AuthenticationUtil.runAs(new RunAsWork<Void>() {

                @Override
                public Void doWork() throws Exception {
                    recordCreateVersion(nf, null);
                    vs.createVersion(nf, vp);
                    return null;
                }
            }, AuthenticationUtil.getRunAsUser());
        } else {
            AuthenticationUtil.runAs(new RunAsWork<Void>() {

                @Override
                public Void doWork() throws Exception {
                    recordCreateVersion(nf, null);
                    vs.createVersion(nf, vp);
                    return null;
                }
            },AuthenticationUtil.getSystemUserName());
        }
        // To here
    }

    /**
     * @see org.alfresco.repo.version.VersionServicePolicies.OnCreateVersionPolicy#onCreateVersion(org.alfresco.service.namespace.QName, org.alfresco.service.cmr.repository.NodeRef, java.util.Map, org.alfresco.repo.policy.PolicyScope)
     */
    public void afterCreateVersion(NodeRef versionableNode, Version version)
    {
        recordCreateVersion(versionableNode, version);
    }

    @SuppressWarnings("unchecked")
    private void recordCreateVersion(NodeRef versionableNode, Version version)
    {
        Map<NodeRef, NodeRef> versionedNodeRefs = (Map<NodeRef, NodeRef>)AlfrescoTransactionSupport.getResource(KEY_VERSIONED_NODEREFS);
        if (versionedNodeRefs == null)
        {
            versionedNodeRefs = new HashMap<NodeRef, NodeRef>();
            AlfrescoTransactionSupport.bindResource(KEY_VERSIONED_NODEREFS, versionedNodeRefs);
        }
        versionedNodeRefs.put(versionableNode, versionableNode);
    }

    /*
     * (non-Javadoc)
     * @see org.alfresco.repo.dictionary.DictionaryListener#onDictionaryInit()
     */
    @Override
    public void onDictionaryInit() {}

    /*
     * (non-Javadoc)
     * @see org.alfresco.repo.dictionary.DictionaryListener#afterDictionaryInit()
     */
    @Override
    public void afterDictionaryInit()
    {
        this.excludedOnUpdatePropQNames = new HashSet<QName>(this.excludedOnUpdateProps.size() * 2);
        this.excludedOnUpdateAssocsQNames = new HashSet<>();
        this.excludedOnUpdateChildAssocsQNames = new HashSet<>();
        for (String prefixString: this.excludedOnUpdateProps)
        {
            try {
                this.excludedOnUpdatePropQNames.add(QName.createQName(prefixString, this.namespacePrefixResolver));
            } catch (Exception e) {/* An unregistered prefix. Ignore and continue */ }
        }
        for (String okPrefixString: this.excludedOnUpdateAssocs)
        {
            try
            {
                this.excludedOnUpdateAssocsQNames.add(QName.createQName(okPrefixString, this.namespacePrefixResolver));
            } catch (Exception e) {/* An unregistered prefix. Ignore and continue */ }
        }
        for (String okPrefixString: this.excludedOnUpdateChildAssocs)
        {
            try
            {
                this.excludedOnUpdateChildAssocsQNames.add(QName.createQName(okPrefixString, this.namespacePrefixResolver));
            } catch (Exception e) {/* An unregistered prefix. Ignore and continue */ }
        }
    }

    /*
     * (non-Javadoc)
     * @see org.alfresco.repo.dictionary.DictionaryListener#afterDictionaryDestroy()
     */
    @Override
    public void afterDictionaryDestroy() {}
}
