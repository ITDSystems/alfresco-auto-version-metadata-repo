# alfresco-auto-version-metadata-repo
Extension adds alternate configurable autoversion politics.
## v0.5

### DONE
* Supports two autoversioning mods - *default* and *custom* (enables with flag **customAutoVersioning**)
* Added association autoversioning.
* Added new blacklist behavior - auotversioning working if changes at least one of non blaklisted properties/associations

Alpha version. All features are working with enabled **customAutoVersioning**. In *custom* mode new versions are creating by users that caused changes.

### Installation
Soon

### TODO
* Tests
* More tests
* Refactoring at some moment

### Usage
All preferences could be set in **alfresco-global.properties**

Supported flags

* **customAutoVersioning** boolean - enables custom behavior

if enabled:

* **autoVersionAssocs** boolean- enables associations autoversioning
* **autoVersionChildAssocs** boolean - enables folder autoversioning on content changes
* **autoAssociationDelay** double(secs) - because you can add many associations at once you may want to have one new version on them. For this you could set autoversion delay in secs for docment.

**Warning** This extension has different from default autoversion logic! You should check the differences carefully before use!
