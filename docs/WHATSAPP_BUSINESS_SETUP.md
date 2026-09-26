Yes. Below is a ready-to-save Markdown document for your project, specifically for setting up **WhatsApp Business Platform / Cloud API for a gym-management SaaS**.

 I’ve kept the verification section practical for an Indian gym software business. Meta’s exact verification flow and requested documents can vary depending on the business and account, so treat the document checklist as **typical/possible requirements rather than a guaranteed list**. Meta’s business tooling is currently accessed through its business platform.  Facebook Business

 Save this as:

```
docs/WHATSAPP_BUSINESS_SETUP.md
```

 # WhatsApp Business Platform Setup Guide

 ## 1\. Purpose

 This document explains how to set up WhatsApp Business Platform / WhatsApp Cloud API for the Gym Management application.

 The application will use WhatsApp to send transactional notifications such as:

 - Membership created
- Membership renewal
- Payment confirmation
- Membership expiry reminder
- Membership expired
- Membership frozen
- Membership cancelled
- Other gym notifications

 The backend communicates with Meta's WhatsApp Cloud API.

```
Gym Management Application
          |
          v
Spring Boot Backend
          |
          v
WhatsAppNotificationAdapter
          |
          v
WhatsAppClient
          |
          v
Meta WhatsApp Cloud API
          |
          v
Gym Member WhatsApp
```

---

 # 2\. What You Need

 Before starting, prepare the following.

 ## Business requirements

 You should have:

 - A real business/gym business name
- Business address
- Business phone number
- Business email address
- A person authorized to represent the business
- Business website, preferably
- Privacy Policy URL
- Terms & Conditions URL
- Description of the gym/software business

 For a SaaS gym-management application, it is preferable to use a real business identity rather than creating a generic/fake business identity.

---

 # 3\. Meta Account

 Create or use a Meta account that will administer the business.

 Go to:

 https://business.facebook.com/

 Create or access your Meta Business account.

 The business account should represent the company/business operating the gym application.

 Do not use a developer's personal identity as the long-term business identity if the application is going into production.

---

 # 4\. Create Meta Business Portfolio

 Inside Meta Business tools:

 1. Create a Business Portfolio.
2. Enter the legal business name.
3. Enter the business email.
4. Enter the business address.
5. Complete the initial business information.

 Use the **legal business name** consistently.

 For example:

```
Legal Business Name:
ABC Fitness Technologies Private Limited

Product:
ABC Gym Management

Website:
https://example.com

Business Email:
support@example.com

Business Address:
Registered business address
```

 The information should match the documents you will provide during verification.

---

 # 5\. Business Verification

 Business verification may be required depending on the WhatsApp/API capabilities and Meta's requirements for your account.

 The verification process can ask Meta to verify:

 - Legal business name
- Business address
- Business phone number
- Website/domain
- Business registration information

 ## Important

 Do not enter different versions of the company name in different places.

 For example, avoid:

```
ABC Fitness
ABC Fitness Technologies
ABC Fitness Pvt Ltd
ABC Gym Software
```

 when your legal registration says:

```
ABC Fitness Technologies Private Limited
```

 Use the legal business name where Meta asks for the legal business information.

---

 # 6\. Documents to Prepare

 Meta may request documents to verify the business.

 Prepare clear, valid copies of the following where applicable.

 ## A. Business registration document

 For an Indian company, this may include documents such as:

 - Certificate of Incorporation
- MCA/company registration information
- Partnership registration documentation
- LLP registration documentation
- Other government-issued business registration documents

 Use the document appropriate to your legal business structure.

---

 ## B. GST documentation

 If the business is GST registered, keep the following available:

 - GST Registration Certificate
- GSTIN

 Example:

```
Legal Business Name: ABC Fitness Technologies Private Limited
GSTIN: XXXXXXXXXXXXXXX
Registered Address: ...
```

 The legal name and address should match the information being submitted to Meta.

---

 ## C. Business PAN

 For an Indian business, keep the applicable PAN documentation available.

 For example:

```
Company PAN
```

 or the appropriate tax identity document for the business structure.

---

 ## D. Business address proof

 Meta may request documentation supporting the business address.

 Possible documents include official business-address documentation such as:

 - Government registration document
- GST registration
- Business license
- Utility bill
- Bank statement
- Other accepted official documentation

 The exact document types accepted can vary during Meta's verification process.

---

 ## E. Business phone number

 Keep the business phone number available.

 This number should be controlled by the business.

 Do not use a phone number that you cannot access during the verification/setup process.

---

 ## F. Business email

 Use a business-controlled email address.

 Prefer:

```
admin@example.com
support@example.com
whatsapp@example.com
```

 rather than a temporary personal email.

---

 # 7\. Website Requirements

 For a production gym application, create a public website.

 At minimum, the website should clearly identify:

```
Company / Business Name
About
Contact
Privacy Policy
Terms & Conditions
```

 Recommended:

```
https://example.com/
https://example.com/about
https://example.com/contact
https://example.com/privacy
https://example.com/terms
```

 The business name shown on the website should be consistent with your Meta business information.

---

 # 8\. Privacy Policy

 Because the gym application stores member information, create a proper privacy policy.

 It should explain that the application may process information such as:

 - Member name
- Phone number
- Email address
- Membership information
- Payment-related information
- Notification preferences
- WhatsApp communication information

 The policy should also explain how users can contact the business regarding their data.

 Example:

```
We use member contact information to provide
membership-related notifications and other services
requested by the gym.
```

 Do not claim that data is never shared if your application actually sends data to third-party providers such as Meta for WhatsApp delivery.

---

 # 9\. Create Meta App

 Go to Meta for Developers:

 https://developers.facebook.com/

 Create a new application.

 Select the appropriate business/app configuration for the WhatsApp integration.

 The application will eventually contain the WhatsApp product.

 Conceptually:

```
Meta Business Portfolio
        |
        +---- Meta App
                 |
                 +---- WhatsApp
                        |
                        +---- Business Account
                        |
                        +---- Phone Number
                        |
                        +---- Templates
```

---

 # 10\. Add WhatsApp Product

 Inside the Meta application:

 1. Add WhatsApp.
2. Create or select the WhatsApp Business Account.
3. Configure the business information.
4. Add a phone number.
5. Complete phone verification.

 Meta will provide identifiers that your backend needs.

 Important values include:

```
Business Account ID
Phone Number ID
Access Token
```

 Your backend primarily needs the Phone Number ID and access token for sending messages.

---

 # 11\. Phone Number

 Use a dedicated business number for production.

 Recommended:

```
+91XXXXXXXXXX
```

 For a production SaaS:

```
WhatsApp Business Number
        |
        v
Meta WhatsApp Business Account
        |
        v
Gym Application
```

 Do not build the production system around a temporary test number.

---

 # 12\. Test Number vs Production Number

 During development, Meta can provide a test environment/number.

 Use it for:

 - API testing
- Template testing
- Backend integration
- Debugging
- Webhook testing

 For production, configure the actual business phone number.

---

 # 13\. Access Token

 The access token is a secret.

 Never put it in:

```
Frontend JavaScript
React code
Angular code
Mobile application
GitHub
Public documentation
```

 Store it in the backend configuration.

 Example:

```
whatsapp:
  phone-number-id: ${WHATSAPP_PHONE_NUMBER_ID}
  access-token: ${WHATSAPP_ACCESS_TOKEN}
  api-version: v25.0
```

 Then configure environment variables:

```
WHATSAPP_PHONE_NUMBER_ID=...
WHATSAPP_ACCESS_TOKEN=...
```

 Do not commit the real token to Git.

---

 # 14\. Backend Configuration

 Example Spring Boot configuration:

```
whatsapp:
  base-url: https://graph.facebook.com
  api-version: v25.0
  phone-number-id: ${WHATSAPP_PHONE_NUMBER_ID}
  access-token: ${WHATSAPP_ACCESS_TOKEN}
```

 Your Java code can then construct:

```
https://graph.facebook.com/v25.0/{PHONE_NUMBER_ID}/messages
```

---

 # 15\. WhatsApp Message Templates

 For business-initiated WhatsApp notifications, create message templates in Meta.

 Examples:

```
MEMBERSHIP_CREATED
MEMBERSHIP_RENEWED
PAYMENT_RECEIVED
MEMBERSHIP_EXPIRING
MEMBERSHIP_EXPIRED
MEMBERSHIP_FROZEN
MEMBERSHIP_CANCELLED
```

 For example:

```
Template Name:
membership_created

Language:
en_US

Body:

Hello {{1}},

Your membership has been created successfully.

Membership:
{{2}}

Valid until:
{{3}}

Thank you.
```

 This template contains three parameters:

```
{{1}} = Member name
{{2}} = Membership/plan information
{{3}} = Expiry date
```

 The API request must provide exactly three body parameters.

---

 # 16\. Template Name

 The template name used by the backend must exactly match the approved Meta template name.

 For example:

```
jaspers_market_order_confirmation_v1
```

 If Meta expects:

```
{{1}}
{{2}}
{{3}}
```

 the backend must send:

```
"parameters": [
  {
    "type": "text",
    "text": "John Doe"
  },
  {
    "type": "text",
    "text": "123456"
  },
  {
    "type": "text",
    "text": "Sep 16, 2026"
  }
]
```

 Do not send zero parameters when the template expects three.

---

 # 17\. Database Configuration

 The notification template database record should contain the WhatsApp configuration.

 Example:

```
template_key:
MEMBERSHIP_CREATED

channel:
WHATSAPP

whatsapp_template_name:
membership_created

whatsapp_language:
en_US

active:
true
```

 Example SQL:

```
INSERT INTO notification_template
(
    tenant_id,
    template_key,
    channel,
    subject,
    body,
    whatsapp_template_name,
    whatsapp_language,
    active,
    public_id,
    created_at,
    updated_at,
    version
)
VALUES
(
    1,
    'MEMBERSHIP_CREATED',
    'WHATSAPP',
    '',
    '',
    'membership_created',
    'en_US',
    TRUE,
    UUID(),
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    0
);
```

 If `body` is defined as `NOT NULL`, use an empty string rather than `NULL`.

---

 # 18\. Membership Notification Flow

 When a membership is created:

```
POST /memberships
       |
       v
MembershipService
       |
       v
Membership saved
       |
       v
NotificationService
       |
       v
Find MEMBERSHIP_CREATED template
       |
       v
Build notification variables
       |
       v
WhatsAppNotificationAdapter
       |
       v
WhatsAppClient
       |
       v
Meta Graph API
       |
       v
WhatsApp member
```

 The notification should not require the frontend to directly communicate with Meta.

---

 # 19\. WhatsApp API Request

 Example:

```
{
  "messaging_product": "whatsapp",
  "to": "918171229667",
  "type": "template",
  "template": {
    "name": "membership_created",
    "language": {
      "code": "en_US"
    },
    "components": [
      {
        "type": "body",
        "parameters": [
          {
            "type": "text",
            "text": "John Doe"
          },
          {
            "type": "text",
            "text": "Gold Membership"
          },
          {
            "type": "text",
            "text": "Sep 16, 2026"
          }
        ]
      }
    ]
  }
}
```

---

 # 20\. Webhooks

 Sending a message and knowing that it was delivered are two different things.

 The API response can indicate that Meta accepted the message.

 For actual status tracking, configure WhatsApp webhooks.

 Your application should handle statuses such as:

```
sent
delivered
read
failed
```

 Recommended flow:

```
WhatsApp API
     |
     v
Message accepted
     |
     v
Meta sends webhook
     |
     +---- sent
     |
     +---- delivered
     |
     +---- read
     |
     +---- failed
```

 Store the WhatsApp message ID in your `outbound_notification` table.

---

 # 21\. Recommended outbound\_notification fields

 For production, consider storing:

```
id
tenant_id
notification_type
channel
recipient
template_key
whatsapp_template_name
whatsapp_language
provider_message_id
status
error_code
error_message
sent_at
delivered_at
read_at
failed_at
created_at
updated_at
```

 This makes it possible to see:

```
Member: John Doe
Notification: MEMBERSHIP_CREATED
Channel: WHATSAPP
Provider Message ID: wamid....
Status: DELIVERED
```

---

 # 22\. Error Handling

 If Meta returns an error, store the provider error.

 For example:

```
HTTP 400
Code: 132000

Number of parameters does not match
```

 This usually means the number of template parameters sent by your backend does not match the template definition.

 For example:

```
Template expects: 3
Backend sends:    0
```

 is invalid.

---

 # 23\. Production Security

 Never commit:

```
WHATSAPP_ACCESS_TOKEN
```

 to Git.

 Do not put the token in frontend code.

 Use:

```
access-token: ${WHATSAPP_ACCESS_TOKEN}
```

 and configure the actual value through environment variables or your deployment secret manager.

 If a production token is accidentally exposed:

 1. Revoke/rotate it.
2. Generate a new token.
3. Update the deployment secret.
4. Restart the backend.

---

 # 24\. Recommended Production Checklist

 ## Meta Business

 - [ ] Meta account created
- [ ] Business Portfolio created
- [ ] Legal business information entered
- [ ] Business verification completed if required
- [ ] Business address verified if requested
- [ ] Business phone verified if requested
- [ ] Business email verified

 ## Documents

 - [ ] Certificate of Incorporation / business registration
- [ ] GST certificate, if applicable
- [ ] Business PAN / applicable tax document
- [ ] Business address proof
- [ ] Business phone access
- [ ] Authorized representative information
- [ ] Any additional document requested by Meta

 ## Website

 - [ ] Company/business name
- [ ] About page
- [ ] Contact page
- [ ] Privacy Policy
- [ ] Terms & Conditions
- [ ] Public HTTPS website
- [ ] Business information consistent with Meta

 ## WhatsApp

 - [ ] WhatsApp Business Account created
- [ ] WhatsApp phone number configured
- [ ] Phone Number ID obtained
- [ ] Access token configured
- [ ] Message templates created
- [ ] Templates approved
- [ ] Test message successful
- [ ] Production number configured

 ## Backend

 - [ ] WhatsAppClient configured
- [ ] Access token stored securely
- [ ] Phone Number ID stored securely
- [ ] Notification templates stored in database
- [ ] Template variables correctly generated
- [ ] WhatsApp parameters correctly mapped
- [ ] Provider message ID stored
- [ ] Error response stored
- [ ] Webhook endpoint implemented
- [ ] Delivery status handled

 ## Frontend

 The frontend should NOT call Meta directly.

 Instead:

```
Frontend
   |
   | create membership
   v
Gym Backend
   |
   | send notification
   v
WhatsApp Cloud API
```

 The frontend can display notification history:

```
Membership Created
WhatsApp
Delivered
```

 but the WhatsApp token must remain exclusively on the backend.

---

 # 25\. Recommended Architecture for the Gym SaaS

 Because this is a multi-tenant gym application, keep the WhatsApp configuration separated from tenant data.

 Recommended:

```
Tenant
   |
   +---- Notification Settings
   |
   +---- Notification Templates
   |
   +---- Outbound Notifications
   |
   +---- WhatsApp Configuration
```

 If each gym will eventually connect its own WhatsApp Business account, do not hard-code one global WhatsApp phone number.

 Instead:

```
Tenant A
   |
   +---- WhatsApp Business Account A
   +---- Phone Number A
   +---- Token A

Tenant B
   |
   +---- WhatsApp Business Account B
   +---- Phone Number B
   +---- Token B
```

 If your SaaS initially operates one WhatsApp number for all gyms, a simpler global configuration can be used.

---

 # 26\. Development vs Production

 During development:

```
Meta Test Number
       |
       v
Spring Boot local server
       |
       v
WhatsApp test recipient
```

 For production:

```
Verified Business
       |
       v
Production WhatsApp Business Account
       |
       v
Production Phone Number
       |
       v
Production Spring Boot Backend
       |
       v
Gym Members
```

 Do not use development credentials in production.

---

 # 27\. Final Setup Sequence

 Follow this order:

```
1. Create Meta Business Portfolio
        ↓
2. Prepare legal business information
        ↓
3. Complete business verification if required
        ↓
4. Create Meta Developer App
        ↓
5. Add WhatsApp
        ↓
6. Create/select WhatsApp Business Account
        ↓
7. Add business phone number
        ↓
8. Verify phone number
        ↓
9. Get Phone Number ID
        ↓
10. Create access token
        ↓
11. Create WhatsApp message template
        ↓
12. Submit template for approval
        ↓
13. Configure template in gym database
        ↓
14. Configure backend credentials
        ↓
15. Test API directly with curl
        ↓
16. Test through MembershipService
        ↓
17. Configure webhook
        ↓
18. Test sent/delivered/read/failed statuses
        ↓
19. Move to production credentials
```

---

 # 28\. Important Note About Verification

 Meta's verification requirements and available WhatsApp features can change by account, business type, country, and product configuration.

 Therefore, if Meta asks for a document not listed above, use the document specifically requested in the Meta verification flow.

 Do not upload altered, fabricated, expired, or unrelated documents.

 For the gym application, the safest approach is to make the following information consistent across your business registration, Meta Business Portfolio, website, and WhatsApp Business Account:

```
Legal business name
Business address
Business phone
Business website
Business email
```

---

 # 29\. Official Resources

 Meta Business:

 https://business.facebook.com/

 Meta for Developers:

 https://developers.facebook.com/

 WhatsApp Business Platform:

 https://developers.facebook.com/docs/whatsapp/

 Meta Business Help Center:

 https://www.facebook.com/business/help/

 Use Meta's official documentation for the current verification requirements and WhatsApp Cloud API setup because those requirements can change.

 One important architectural point for our gym app: **if every gym tenant will eventually have its own WhatsApp number/account, design the database for per-tenant WhatsApp credentials now**. If all gyms will send through one number owned by your SaaS company, the current global configuration is much simpler.