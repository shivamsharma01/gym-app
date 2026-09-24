# SMS Notification Requirements & Specification

 **Project:** Gym Management System\
 **Document Type:** SMS Notification Requirements\
 **Status:** Pending Gym Owner Confirmation\
 **Monthly SMS Limit:** Approximately **500 SMS/month**

---

 ## 1. Current System Overview

 The current gym management system has the following characteristics:

 - Members **do not have a mobile application**.
- Only **Admin and Staff** users access the frontend application.
- There is **no OTP-based authentication** for members.
- Member communication will be through **SMS**.
- Payments are recorded manually by Admin/Staff.
- There is **no online payment gateway**.
- Supported payment methods are:
  - CARD
  - CASH
  - UPI
  - BANK TRANSFER
  - OTHER
- Admin can create and manage Staff accounts.
- The available SMS quota is approximately **500 SMS per month**.

---

 # 2\. Objective

 The purpose of this document is to identify which events should trigger an SMS to a gym member.

 Since the system has a limited quota of approximately **500 SMS/month**, SMS should be reserved for important member-facing events.

 The gym owner should confirm which notifications are required before implementation.

---

 # 3\. SMS Requirements — Gym Owner Confirmation

 Please select the notifications required by the gym.

---

 ## 3.1 Membership Notifications

 | Notification | Required | Owner Selection |
| --- | --- | --- |
| New membership created | ☐ |  |
| Membership renewed | ☐ |  |
| Membership expiry reminder — 7 days before | ☐ |  |
| Membership expiry reminder — 3 days before | ☐ |  |
| Membership expires today | ☐ |  |
| Membership expired | ☐ |  |
| Membership dates changed by staff | ☐ |  |
| Membership frozen | ☐ |  |
| Membership unfrozen | ☐ |  |
| Membership cancelled | ☐ |  |

### Recommended

 For the initial version, we recommend:

 - ✅ New membership
- ✅ Membership renewal
- ✅ 7-day expiry reminder

 Other membership events can be enabled later if required.

---

 # 4\. Payment Notifications

 Payments are currently recorded manually by Admin/Staff.

 The system supports:

 - CARD
- CASH
- UPI
- BANK TRANSFER
- OTHER

 There is currently **no payment gateway**.

---

 ## 4.1 Payment SMS

 Please confirm whether an SMS should be sent when payment is recorded.

 | Notification | Required | Owner Selection |
| --- | --- | --- |
| Payment received | ☐ |  |
| Payment receipt/details | ☐ |  |
| Payment for new membership | ☐ |  |
| Payment for renewal | ☐ |  |
| No payment SMS | ☐ |  |

---

 ## 4.2 Payment Method

 Should the payment SMS be sent for:

 | Payment Method | Send SMS? |
| --- | --- |
| CARD | ☐ |
| CASH | ☐ |
| UPI | ☐ |
| BANK TRANSFER | ☐ |
| OTHER | ☐ |

### Important Question

 Should the payment SMS be sent for **all payment methods**, or only selected payment methods?

 **Owner decision:**

 > ---

---

 # 5\. Example Payment SMS

 If the owner selects payment notifications, an SMS could be:

 > **Payment Received:** ₹1,500 received for your membership at ABC Fitness. Payment method: UPI. Thank you.

 Possible template variables:

```
{{memberName}}
{{amount}}
{{paymentMethod}}
{{gymName}}
{{membershipEndDate}}
```

 Example:

```
Payment Received: Hi Rahul, ₹1,500 received for your membership at ABC Fitness. Payment method: UPI. Thank you.
```

---

 # 6\. General Gym Notifications

 Please confirm whether the gym wants to send SMS for general communications.

 | Notification | Required | Owner Selection |
| --- | --- | --- |
| Gym announcements | ☐ |  |
| Special offers/promotions | ☐ |  |
| Holiday/closure announcements | ☐ |  |
| Important notices | ☐ |  |
| Other | ☐ |  |

### Other

 > ---

 > ---

---

 # 7\. Staff/Admin Actions

 The following actions are performed by Staff/Admin.

 Please confirm which actions should generate an SMS to the member.

 | Staff/Admin Action | SMS Required? |
| --- | --- |
| Staff creates membership | ☐ |
| Staff renews membership | ☐ |
| Staff changes membership dates | ☐ |
| Staff records payment | ☐ |
| Staff freezes membership | ☐ |
| Staff unfreezes membership | ☐ |
| Staff cancels membership | ☐ |

### Recommended

 The fact that a **staff member performed the action does not necessarily need to be mentioned in the SMS**.

 For example, instead of:

 > Staff member John changed your membership.

 Use:

 > Membership Updated: Hi Rahul, your membership expiry date has been updated to 20 Oct 2026.

 This keeps the SMS simple and professional.

---

 # 8. SMS Priority

 Because the system has approximately **500 SMS/month**, please prioritize the requirements.

 ## Priority 1 — Must Have

 These are the most important SMS notifications.

---

 ## Priority 2 — If SMS Quota Is Available

---

 ## Not Required

---

 # 9\. Recommended Initial SMS Configuration

 For the initial production version, the recommended configuration is:

 | Event | SMS | Priority |
| --- | --- | --- |
| New membership | ✅ | High |
| Membership renewal | ✅ | High |
| Payment received | ✅ | High |
| Membership expires in 7 days | ✅ | High |
| Membership expires in 3 days | ❌ | Medium |
| Membership expires today | ❌ | Medium |
| Membership expired | ❌ | Medium |
| Membership date changed | ❌ | Low |
| Membership frozen | ❌ | Low |
| Membership unfrozen | ❌ | Low |
| Membership cancelled | ❌ | Low |
| General announcements | ❌ | Low |
| Promotions | ❌ | Low |

This is only a recommendation. **The gym owner's selection is the final requirement.**

---

 # 10\. Recommended SMS Templates

 ## 10.1 New Membership

 ### Template Key

```
MEMBERSHIP_CREATED
```

 ### Channel

```
SMS
```

 ### Subject

```
Membership Activated
```

 ### Body

```
Membership Activated: Hi {{memberName}}, your {{gymName}} membership is active from {{startDate}} to {{expiryDate}}.
```

 ### Example

 > Membership Activated: Hi Rahul, your ABC Fitness membership is active from 15 Sep 2026 to 14 Oct 2026.

---

 # 11\. Membership Renewal

 ### Template Key

```
MEMBERSHIP_RENEWED
```

 ### Channel

```
SMS
```

 ### Subject

```
Membership Renewed
```

 ### Body

```
Membership Renewed: Hi {{memberName}}, your {{gymName}} membership has been renewed until {{expiryDate}}.
```

 ### Example

 > Membership Renewed: Hi Rahul, your ABC Fitness membership has been renewed until 14 Nov 2026.

---

 # 12\. Payment Received

 ### Template Key

```
PAYMENT_RECEIVED
```

 ### Channel

```
SMS
```

 ### Subject

```
Payment Received
```

 ### Body

```
Payment Received: Hi {{memberName}}, ₹{{amount}} received for your membership at {{gymName}}. Payment method: {{paymentMethod}}. Thank you.
```

 ### Example

 > Payment Received: Hi Rahul, ₹1,500 received for your membership at ABC Fitness. Payment method: UPI. Thank you.

---

 # 13. Membership Expiry Reminder

 ### Template Key

```
EXPIRY_REMINDER
```

 ### Channel

```
SMS
```

 ### Subject

```
Membership Expiring
```

 ### Body

```
Membership Expiry: Hi {{memberName}}, your {{gymName}} membership expires on {{expiryDate}}. Please renew to continue your membership.
```

 ### Example

 > Membership Expiry: Hi Rahul, your ABC Fitness membership expires on 21 Sep 2026\. Please renew to continue your membership.

 ### Recommended Trigger

 Send **one SMS 7 days before expiry**.

---

 # 14\. Membership Expired

 If the gym owner selects this notification:

 ### Template Key

```
MEMBERSHIP_EXPIRED
```

 ### Channel

```
SMS
```

 ### Subject

```
Membership Expired
```

 ### Body

```
Membership Expired: Hi {{memberName}}, your {{gymName}} membership expired on {{expiryDate}}. Please contact the gym to renew.
```

---

 # 15. Membership Date Changed

 If selected by the owner:

 ### Template Key

```
MEMBERSHIP_UPDATED
```

 ### Channel

```
SMS
```

 ### Body

```
Membership Updated: Hi {{memberName}}, your {{gymName}} membership expiry date has been updated to {{expiryDate}}.
```

 ### Example

 > Membership Updated: Hi Rahul, your ABC Fitness membership expiry date has been updated to 20 Oct 2026.

 This should normally be **Low Priority** because changing dates may happen relatively frequently.

---

 # 16\. Firebase / Push Notification Decision

 ## Firebase FCM is NOT required for member notifications.

 The reason is:

```
Members
   ↓
No mobile application
   ↓
No member device token
   ↓
Firebase Push Notification is not useful
```

 Firebase FCM would make sense if the gym later creates a member mobile application.

 For the current system, the recommended architecture is:

```
Admin/Staff Frontend
        ↓
Spring Boot Backend
        ↓
Business Event
        ↓
NotificationService
        ↓
SMS Provider
        ↓
Member's Mobile Number
        ↓
SMS
```

---

 # 17\. Payment Notification Flow

 For example, Staff records:

```
Member: Rahul
Amount: ₹1,500
Payment Method: UPI
```

 The system performs:

```
Staff
 ↓
Records Payment
 ↓
PaymentService
 ↓
Payment Saved
 ↓
Payment Event
 ↓
NotificationService
 ↓
SMS Provider
 ↓
Rahul's Mobile Number
 ↓
SMS
```

 Example:

```
Payment Received: Hi Rahul, ₹1,500 received for your membership at ABC Fitness. Payment method: UPI. Thank you.
```

---

 # 18\. Membership Notification Flow

 When Staff creates a membership:

```
Staff
 ↓
MembershipService
 ↓
Membership Saved
 ↓
MembershipChangedEvent
 ↓
NotificationService
 ↓
SMS Provider
 ↓
Member Mobile Number
 ↓
SMS
```

---

 # 19\. Expiry Reminder Flow

 Expiry reminders are different because they are time-based.

 Example:

```
Membership expiry:
21 Sep 2026
```

 Scheduler:

```
14 Sep 2026
      ↓
Scheduler checks memberships
      ↓
Find membership expiring in 7 days
      ↓
NotificationService
      ↓
SMS Provider
      ↓
Member
      ↓
SMS
```

 The scheduler should ensure that the same reminder is not sent repeatedly.

---

 # 20\. Notification Template Architecture

 The existing `NotificationTemplate` entity is suitable for this system.

 It contains:

```
templateKey
channel
subject
body
```

 Example records:

 | templateKey | channel | subject | body |
| --- | --- | --- | --- |
| MEMBERSHIP\_CREATED | SMS | Membership Activated | Membership Activated: Hi... |
| MEMBERSHIP\_RENEWED | SMS | Membership Renewed | Membership Renewed: Hi... |
| PAYMENT\_RECEIVED | SMS | Payment Received | Payment Received: Hi... |
| EXPIRY\_REMINDER | SMS | Membership Expiring | Membership Expiry: Hi... |
| MEMBERSHIP\_EXPIRED | SMS | Membership Expired | Membership Expired: Hi... |
| MEMBERSHIP\_UPDATED | SMS | Membership Updated | Membership Updated: Hi... |

---

 # 21\. Template Variables

 The SMS system can support dynamic values.

 Recommended variables:

```
{{memberName}}
{{gymName}}
{{amount}}
{{paymentMethod}}
{{startDate}}
{{expiryDate}}
{{daysRemaining}}
```

 Example template:

```
Payment Received: Hi {{memberName}}, ₹{{amount}} received for your membership at {{gymName}}. Payment method: {{paymentMethod}}. Thank you.
```

 After rendering:

```
Payment Received: Hi Rahul, ₹1,500 received for your membership at ABC Fitness. Payment method: UPI. Thank you.
```

---

 # 22\. Existing API Support

 The existing notification-template API can be used to manage templates:

```
GET /api/v1/notification-templates
```

 and:

```
PUT /api/v1/notification-templates
```

 Therefore, SMS text does not need to be permanently hard-coded into the Java source code.

---

 # 23. SMS Quota Considerations

 The available quota is approximately:

```
500 SMS / month
```

 ThereforeFor example, if a staff member changes:

 ### Avoid unnecessary SMS

 For example, if a staff member changes a membership date three times:

```
Change 1 → SMS
Change 2 → SMS
Change 3 → SMS
```

 That consumes **3 SMS** for one member.

 Similarly, sending:

```
7-day reminder
3-day reminder
1-day reminder
expired notification
```

 can consume **4 SMS per membership**.

 With 100 members, that could already result in:

```
100 × 4 = 400 SMS
```

 leaving very little quota for payments, new memberships, and renewals.

---

 # 24\. Recommended Quota Strategy

 With approximately 500 SMS/month, start with:

```
1. New Membership
2. Renewal
3. Payment Received
4. One Expiry Reminder
```

 Recommended expiry reminder:

```
7 days before expiry
```

 Avoid multiple expiry reminders initially.

---

 # 25\. Important: SMS Recipient

 SMS will be sent to the **mobile phone number stored against the member**.

 The flow is:

```
Member
  ↓
Mobile Number
  ↓
Member database record
  ↓
NotificationService
  ↓
SMS Provider
  ↓
Mobile Network
  ↓
Member's Phone
```

 The member does **not** need:

 - Mobile application
- Login
- OTP
- Firebase
- Internet connection
- Gym frontend access

 They only need a valid mobile number capable of receiving SMS.

---

 # 26\. SMS Provider

 The backend will need an SMS provider to actually deliver the SMS.

 Examples include:

 - MSG91
- Twilio
- Other India-supported SMS providers

 The final provider should be selected based on:

 - Cost per SMS
- Monthly quota
- DLT/template requirements
- Delivery reports
- API reliability
- Sender ID requirements
- Promotional vs transactional messaging support

 The SMS provider should **not be tightly coupled** to the business logic.

 Recommended architecture:

```
NotificationService
       ↓
NotificationChannelAdapter
       ↓
SMS Adapter
       ↓
SMS Provider
```

 This allows the provider to be changed later without rewriting the membership/payment logic.

---

 # 27\. Final Owner Confirmation

 Please confirm the following:

 ### Membership

 - [ ] New membership
- [ ] Renewal
- [ ] 7-day expiry reminder
- [ ] 3-day expiry reminder
- [ ] Expiry today
- [ ] Expired
- [ ] Date changed
- [ ] Frozen
- [ ] Unfrozen
- [ ] Cancelled

 ### Payment

 - [ ] Payment received
- [ ] Payment receipt/details
- [ ] New membership payment
- [ ] Renewal payment
- [ ] No payment SMS

 ### Payment Methods

 - [ ] Card
- [ ] Cash
- [ ] UPI
- [ ] Bank Transfer
- [ ] Other

 ### General

 - [ ] Announcements
- [ ] Promotions
- [ ] Holiday/closure
- [ ] Important notices
- [ ] Other

---

 # 28\. Recommended Initial Production Scope

 Unless the gym owner requests otherwise, the recommended first implementation is:

```
┌───────────────────────────────┐
│       SMS Notifications       │
├───────────────────────────────┤
│                               │
│  ✅ New Membership            │
│  ✅ Membership Renewal        │
│  ✅ Payment Received          │
│  ✅ 7-Day Expiry Reminder     │
│                               │
│  ❌ Multiple Expiry Reminders │
│  ❌ Promotions                │
│  ❌ General Announcements     │
│  ❌ Freeze/Unfreeze SMS       │
│  ❌ Date Change SMS           │
│                               │
└───────────────────────────────┘
```

 This keeps the initial SMS usage **focused on financially and operationally important member communications** while staying within the approximately **500 SMS/month** limit.

---

 # 30\. Final Technical Architecture

```
                    ┌──────────────────────┐
                    │    Admin / Staff     │
                    │      Frontend        │
                    └──────────┬───────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │    Spring Boot       │
                    │      Backend         │
                    └──────────┬───────────┘
                               │
              ┌────────────────┼────────────────┐
              │                │                │
              ▼                ▼                ▼
       MembershipService  PaymentService    Scheduler
              │                │                │
              ▼                ▼                ▼
       MembershipEvent    PaymentEvent    Expiry Check
              │                │                │
              └────────────────┼────────────────┘
                               ▼
                    ┌──────────────────────┐
                    │ NotificationService  │
                    └──────────┬───────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │ NotificationTemplate │
                    └──────────┬───────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │    SMS Adapter       │
                    └──────────┬───────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │    SMS Provider      │
                    │ 2factor/infobip /etc │
                    └──────────┬───────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │   Member Mobile No.  │
                    └──────────────────────┘
```

 **Conclusion:** For the current system, **SMS is the correct member-notification channel**. Firebase/FCM should be removed from the member-notification design unless you later introduce a member mobile app.