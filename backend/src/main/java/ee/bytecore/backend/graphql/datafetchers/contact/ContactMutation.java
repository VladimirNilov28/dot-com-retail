package ee.bytecore.backend.graphql.datafetchers.contact;

import ee.bytecore.backend.security.CurrentUserProvider;
import ee.bytecore.backend.services.ContactService;

import com.netflix.dgs.codegen.generated.types.ContactFieldError;
import com.netflix.dgs.codegen.generated.types.ContactMessageInput;
import com.netflix.dgs.codegen.generated.types.ContactMessageResult;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;

@DgsComponent
public class ContactMutation {

    private final ContactService contactService;
    private final CurrentUserProvider currentUserProvider;

    public ContactMutation(ContactService contactService, CurrentUserProvider currentUserProvider) {
        this.contactService = contactService;
        this.currentUserProvider = currentUserProvider;
    }

    @DgsMutation
    public ContactMessageResult submitContactMessage(@InputArgument ContactMessageInput input) {
        var result = contactService.submit(
                new ContactService.Submission(
                        input.getName(),
                        input.getEmail(),
                        input.getSubject() == null ? null : input.getSubject().name(),
                        input.getMessage(),
                        input.getWebsite()),
                currentUserProvider.getCurrentUserId());
        return ContactMessageResult.newBuilder()
                .accepted(result.accepted())
                .reference(result.reference())
                .fieldErrors(result.fieldErrors().stream()
                        .map(error -> ContactFieldError.newBuilder()
                                .field(error.field())
                                .message(error.message())
                                .build())
                        .toList())
                .build();
    }
}
