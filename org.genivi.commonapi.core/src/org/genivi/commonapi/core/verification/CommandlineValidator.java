package org.genivi.commonapi.core.verification;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.eclipse.emf.common.util.Diagnostic;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.xtext.diagnostics.Severity;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.util.IAcceptor;
import org.eclipse.xtext.validation.CheckMode;
import org.eclipse.xtext.validation.DiagnosticConverterImpl;
import org.eclipse.xtext.validation.IResourceValidator;
import org.eclipse.xtext.validation.Issue;
import org.eclipse.xtext.validation.ValidationMessageAcceptor;
import org.franca.core.dsl.FrancaIDLRuntimeModule;
import org.franca.core.dsl.FrancaImportsProvider;
import org.franca.core.franca.FModel;
import org.franca.core.franca.Import;
import org.franca.core.utils.ModelPersistenceHandler;
import org.franca.deploymodel.dsl.FDeployImportsProvider;
import org.franca.deploymodel.dsl.fDeploy.FDModel;

import com.google.inject.Guice;
import com.google.inject.Injector;

public class CommandlineValidator {

	private ValidationMessageAcceptor cliMessageAcceptor;
	private List<String> ignoreList;
	protected static final String DEPLOYMENT_SPEC = "deployment_spec.fdepl";
	protected static final String UNKNOWN_DEPLOYMENT_SPEC = "Couldn't resolve reference to FDSpecification";
	private ResourceSet resourceSet;
	private boolean hasError = false;

	public CommandlineValidator(ValidationMessageAcceptor cliMessageAcceptor) {
		this.cliMessageAcceptor = cliMessageAcceptor;
		resourceSet = new ResourceSetImpl();
		ignoreList = new ArrayList<String>();
	}

	private void showError(String message) {
		hasError = true;
		cliMessageAcceptor.acceptError(message, null, null, 0, null, (String[])null);
	}

	private void showWarning(String message) {
		cliMessageAcceptor.acceptWarning(message, null, null, 0, null, (String[])null);
	}

    private void showInfo(String message) {
        cliMessageAcceptor.acceptInfo(message, null, null, 0, null, (String[])null);
    }

	public void addIgnoreString(String ingnoreText) {
		ignoreList.add(ingnoreText);
	}

	public EObject loadResource(Resource resource) {
		EObject model = null;
		try {
			resource.load(Collections.EMPTY_MAP);
			model = resource.getContents().get(0);
		} catch (IOException e) {
			showError("Could not load resource: " + resource.getURI().toFileString());
			return null;
		}
		// check for resource errors, that have been detected during load
		for (org.eclipse.emf.ecore.resource.Resource.Diagnostic error : resource.getErrors()) {
			showError(error.getMessage() + " in " + resource.getURI().lastSegment() + " line: " + error.getLine());
		}
		return model;
	}

	public void validateImports(FModel model, URI fidlUri) {
		for (Import inport : model.getImports()) {
			// check existence of imported fidl files
			if (inport.getImportURI().endsWith("fidl")) {
				URI importUri = URI.createURI(inport.getImportURI());
				URI fullImportUri = importUri.resolve(fidlUri);
				File file = new File(fullImportUri.toFileString());
				if (!file.exists()) {
					showError("Imported file does not exist: " + inport.getImportURI());
				}
			}
		}
	}

	public void validateImports(FDModel model, URI fdeplUri) {

		for (org.franca.deploymodel.dsl.fDeploy.Import inport : model
				.getImports()) {
			URI importUri = URI.createURI(inport.getImportURI());
			URI fullImportUri = importUri.resolve(fdeplUri);
			try {
				new File(fullImportUri.toFileString());
			} catch (Exception e) {
				// - tolerate "import ..../xxx_deployment_spec.fdepl"
				if (inport.getImportURI().endsWith(DEPLOYMENT_SPEC)) {
					// dont log an error, if the deployment spec could not be found
				} else {
					showError("Imported file does not exist: " + inport.getImportURI());
				}
			}
		}
	}

	/**
	 * validate the contents of the fidl/fdepl file
	 * @param resource
	 */
	public void validateResource(Resource resource) {
		IResourceServiceProvider resourceServiceProvider = IResourceServiceProvider.Registry.INSTANCE
				.getResourceServiceProvider(resource.getURI());
		if (resourceServiceProvider != null) {
			IResourceValidator resourceValidator = resourceServiceProvider
					.getResourceValidator();
			Collection<Issue> issues = resourceValidator.validate(resource,
					CheckMode.ALL, null);
			if (!issues.isEmpty()) {
				for (Issue issue : issues) {
					if (issue.getSeverity() == Severity.ERROR) {
						// ignore certain errors due to unknown deployment specs (SomeIP/DBus)
						if(isErrorToIgnore(issue)) {
							continue;
						}
						showError(issue.toString());
					} else {
						showWarning(issue.toString());
					}
				}
			}
		}
	}


	/**
	 * validate the contents of the fidl/fdepl file and all its imports
	 * @param resource
	 */
	public void validateResourceWithImports(Resource resource) {

		validateResource(resource);

		EObject model = loadResource(resource);

		if (model != null) {
			if (model instanceof FModel) {
				FModel fmodel = (FModel) model;

				validateImports(fmodel, resource.getURI());

				if (!hasError) {
					for (Import fimport : fmodel.getImports()) {
						String uriString = fimport.getImportURI();
						URI importUri = URI.createURI(uriString);
						//System.out.println("Validating import " + importUri.lastSegment());
						URI resolvedUri = importUri.resolve(resource.getURI());
						Resource importedResource = resourceSet.getResource(
								resolvedUri, true);
						validateResourceWithImports(importedResource);
					}
				}
			}
		}
	}


	private boolean isErrorToIgnore(Issue issue) {
		for(String ignoreString : ignoreList) {
			if(issue.toString().contains(ignoreString)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Validate the deployment on a global scope by loading all referenced FDEPL and FIDL files and by performing deployment
	 * specific validations which need to run at such a scope (e.g. checking interface dependencies between FDEPL and FIDL files)
	 *
	 * @param resourcePathUri URI to the FDEPL file
	 * @return
	 */
	public boolean validateDeployment(URI resourcePathUri)
    {
        if ("fdepl".equals(resourcePathUri.fileExtension()))
        {
            // Load the FIDL and all imported FIDL files, or the FDEPL and all imported FDEPL/FIDL files.
            //
            ModelPersistenceHandler.registerFileExtensionHandler("fidl", new FrancaImportsProvider());
            ModelPersistenceHandler.registerFileExtensionHandler("fdepl", new FDeployImportsProvider());
            Injector injector = Guice.createInjector(new FrancaIDLRuntimeModule());
            ModelPersistenceHandler modelPersistenceHandler = new ModelPersistenceHandler(injector.getInstance(ResourceSet.class));
            URI rootPathUri = URI.createPlatformResourceURI("/", true);
            modelPersistenceHandler.loadModel(resourcePathUri, rootPathUri);

            if (!validateDeployment(modelPersistenceHandler.getResourceSet()))
                return false;
        }
        return true;
    }

    protected boolean validateDeployment(ResourceSet resourceSet)
    {
        boolean hasValidationError = false;

        List<FDModel> fdeplModels = new ArrayList<FDModel>();
        for (Resource resource : resourceSet.getResources())
        {
            for (EObject eObject : resource.getContents())
            {
                if (eObject instanceof FDModel)
                {
                    fdeplModels.add((FDModel)eObject);
                }
            }
        }

        List<Diagnostic> diags = validateDeployment(fdeplModels);
        if (diags != null)
        {
            int numErrors = 0, numWarnings = 0;
            final List<Issue> issues = new ArrayList<Issue>();
            IAcceptor<Issue> acceptor = new IAcceptor<Issue>()
            {
                @Override
                public void accept(Issue issue)
                {
                    issues.add(issue);
                }
            };
            DiagnosticConverterImpl converter = new DiagnosticConverterImpl();
            for (Diagnostic diag : diags)
            {
                converter.convertValidatorDiagnostic(diag, acceptor);
                if (diag.getSeverity() == Diagnostic.ERROR) {
                    numErrors++;
                    hasValidationError = true;
                }
                else if (diag.getSeverity() == Diagnostic.WARNING)
                    numWarnings++;
            }
            if (!outputIssues(issues))
                hasValidationError = true;
            showInfo("Validaton of deployment finished with: " + numErrors + " errors, " + numWarnings + " warnings.");
        }

        return !hasValidationError;
    }

    private boolean outputIssues(Collection<Issue> issues)
    {
        boolean hasValidationError = false;
        for (Issue issue : issues)
        {
            // 'Issue' messages already add their own message type prefix, but our issue acceptors will add another one.
            //
            String msg = issue.toString();
            if (issue.getSeverity() == Severity.ERROR) {
                hasValidationError = true;
                showError(stripMessageType(msg, "ERROR:"));
            }
            else if (issue.getSeverity() == Severity.WARNING)
                showWarning(stripMessageType(msg, "WARNING:"));
            else
                showInfo(msg = stripMessageType(msg, "INFO:"));
        }
        return !hasValidationError;
    }

    private String stripMessageType(String msg, String type)
    {
        if (msg.startsWith(type))
            msg = msg.substring(type.length()).trim();
        return msg;
    }

    /**
     * Perform deployment specific validation.
     *
     * @param fdepls List of FDEPL files. This list contains already all resolved FDEPL imported files.
     * @return List of {@link org.eclipse.emf.common.util.Diagnostic}. 'null' if function is not implemented.
     */
    protected List<Diagnostic> validateDeployment(List<FDModel> fdepls)
    {
        return null;
    }
}
