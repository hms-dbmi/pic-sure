define(["backbone","handlebars",  "application/addApplication", "text!application/applicationManagement.hbs", "text!application/applicationMenu.hbs", "text!application/applicationTable.hbs", "text!options/modal.hbs", "picSure/applicationFunctions", "util/notification", "header/header"],
		function(BB, HBS, AddApplicationView, template, applicationMenuTemplate, applicationTableTemplate, modalTemplate, applicationFunctions, notification, header){
	var applicationManagementModel = BB.Model.extend({
	});

	var applicationManagementView = BB.View.extend({
		// connections : connections,
		template : HBS.compile(template),
		crudApplicationTemplate : HBS.compile(applicationMenuTemplate),
		modalTemplate : HBS.compile(modalTemplate),
		initialize : function(opts){
			HBS.registerHelper('fieldHelper', function(application, connectionField){
				if (application.generalMetadata == null || application.generalMetadata === '') {
                    return "NO_GENERAL_METADATA";
                }
                else {
					return JSON.parse(application.generalMetadata)[connectionField.id];
				}
			});
            HBS.registerHelper('displayEmail', function(application){
				var c = application.connectionId;
                var emailField = _.where(connections, {id: application.connectionId})[0].emailField;
				if (!application.email) {
                    return JSON.parse(application.generalMetadata)[emailField];
				}
                return application.email;
            });
		},
		events : {
			"click .add-application-button":   "addApplicationMenu",
			"click #edit-application-button":  "editApplicationMenu",
			"click .close":             "closeDialog",
			"click #cancel-application-button":"closeDialog",
			"click .application-row":          "showApplicationAction",
			"click #delete-application-button":"deleteApplication",
			"click #app-token-refresh-button":"refreshApplicationToken",
			"click #app-token-copy-button":"copyApplicationToken",
			"submit":                   "saveApplicationAction"
		},
        copyApplicationToken: function(){
            var sel = getSelection();
            var range = document.createRange();

            // this if for supporting chrome, since chrome will look for value instead of textContent
            document.getElementById("application_token_textarea").value = document.getElementById("application_token_textarea").textContent;

            range.selectNode(document.getElementById("application_token_textarea"));
            sel.removeAllRanges();
            sel.addRange(range);
            document.execCommand("copy");

			$("#app-token-copy-button").html("COPIED");
		},
		refreshApplicationToken: function(event){
			var uuid = event.target.attributes.uuid.value;

            notification.showConfirmationDialog(function () {
                applicationFunctions.refreshToken(uuid,function(response){
                    var token = response.token;
                    $("#application_token_textarea", this.$el).html(token);
                    $("#app-token-copy-button").html("COPY");
                }.bind(this));
			}.bind(this), 'center', 'Refresh will inactivate the old token!! Do you want to continue?');

		},
		displayApplications: function (result, view) {
			this.applicationTableTemplate = HBS.compile(applicationTableTemplate);
			$('.application-data', this.$el).html(this.applicationTableTemplate({applications:result}));

		},
		addApplicationMenu: function (result) {
			applicationFunctions.fetchApplications(this, function(applications,view){
				view.showAddApplicationMenu({applications : applications}, view);
			});
		},
		showAddApplicationMenu: function(result, view) {
            $("#modal-window", this.$el).html(this.modalTemplate({title: "Add Application"}));
            $("#modalDialog", this.$el).show();
            var addApplicationView = new AddApplicationView({el:$('.modal-body'), managementConsole: this, applications:result}).render();
		},
		editApplicationMenu: function (events) {
			$(".modal-body", this.$el).html(this.crudApplicationTemplate({createOrUpdateApplication: true, application: this.model.get("selectedApplication")}));
			// this.applyCheckboxes();
		},
		showApplicationAction: function (event) {
			var uuid = event.target.id;

			applicationFunctions.showApplicationDetails(uuid, function(result) {
				this.model.set("selectedApplication", result);
				$("#modal-window", this.$el).html(this.modalTemplate({title: "Application Info"}));
				$("#modalDialog", this.$el).show();
				$(".modal-body", this.$el).html(this.crudApplicationTemplate({createOrUpdateApplication: false, application: this.model.get("selectedApplication")}));
			}.bind(this));
		},
        applyCheckboxes: function () {
            var checkBoxes = $(":checkbox", this.$el);
            var applicationRoles = this.model.get("selectedApplication").roles;
            _.each(checkBoxes, function (roleCheckbox) {
                _.each(applicationRoles, function(role){
                    if (role.name === roleCheckbox.name){
                        roleCheckbox.checked = true;
                    }
                });
            })
        },
        saveApplicationAction: function (e) {
            e.preventDefault();
            var uuid = this.$('input[name=application_name]').attr('uuid');
            var name = this.$('input[name=application_name]').val();
            var description = this.$('input[name=application_description]').val();
			var url = this.$('input[name=application_url]').val();

            var application;
            var requestType;
            if (this.model.get("selectedApplication") != null && this.model.get("selectedApplication").uuid.trim().length > 0) {
                requestType = "PUT";
            }
            else {
                requestType = "POST";
            }

            application = [{
                uuid: uuid,
                name: name,
                description: description,
				url: url,
                privileges: this.model.get("selectedApplication").privileges
            }];

            applicationFunctions.createOrUpdateApplication(application, requestType, function(result) {
                console.log(result);
				this.render();
				this.updateHeader(application);
            }.bind(this));
        },
		deleteApplication: function (event) {
			var uuid = this.$('input[name=application_name]').attr('uuid');
			var url = this.$('input[name=application_url]').val();
			notification.showConfirmationDialog(function () {
				applicationFunctions.deleteApplication(uuid, function (response) {
					this.render()
					this.updateHeader({uuid: uuid, url: url});
				}.bind(this));
			}.bind(this));
		},
		// getApplicationApplications: function (stringApplications) {
		// 	var applications = stringApplications.split(",").map(function(item) {
		// 		return item.trim();
		// 	});
		// 	this.model.get("selectedApplication").applications = applications;
		// },
		closeDialog: function () {
			// cleanup
			this.model.unset("selectedApplication");
			$("#modalDialog").hide();
		},
		updateHeader: function(application) {
			if (application && application.url)
				header.View.render();
		},
		render : function(){
			this.$el.html(this.template({}));
			applicationFunctions.fetchApplications(this, function(applications){
				this.displayApplications.bind(this)
				(
						applications
				);
			}.bind(this));
		}
	});

	return {
		View : applicationManagementView,
		Model: applicationManagementModel
	};
});
