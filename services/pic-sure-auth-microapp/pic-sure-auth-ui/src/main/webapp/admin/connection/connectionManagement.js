define(["backbone","handlebars", "text!connection/connectionManagement.hbs", "text!connection/connectionTable.hbs", "text!options/modal.hbs", "picSure/picsureFunctions", "text!connection/addConnection.hbs", "common/session", "util/notification"],
		function(BB, HBS,  template, connectionTableTemplate, modalTemplate, picsureFunctions, crudConnectionTemplate, session, notification){
	var connectionManagementModel = BB.Model.extend({
	});

	var ConnectionModel = BB.Model.extend({
        defaults : {
            uuid : null,
            label : null,
            id : null,
            subPrefix: null,
			requiredFields: [{label: null, id: null}]
        }
    });

	var connectionManagementView = BB.View.extend({
        template : HBS.compile(template),
		modalTemplate : HBS.compile(modalTemplate),
        theConnectionTemplate: HBS.compile(crudConnectionTemplate),
		initialize : function(opts){
			this.connections = JSON.parse(sessionStorage.connections);
            this.connections.forEach(function (connection) {
               // connection.requiredFields = JSON.parse(connection.requiredFields);
			})
		},
		events : {
			"click .add-connection-button":   	"addConnectionMenu",
            "click #add-field-button":			"addConnectionField",
			"click #edit-button":  				"editConnectionMenu",
            "click #delete-button":				"deleteConnection",
			"click .close":             		"closeDialog",
			"click #cancel-button":				"closeDialog",
			"click .selection-row":     		"showConnectionAction",
			"submit":                   		"saveConnectionAction"
		},
		displayConnections: function (result, view) {
			this.connectionTableTemplate = HBS.compile(connectionTableTemplate);
			$('.connection-data', this.$el).html(this.connectionTableTemplate({connections:result}));

		},
		addConnectionMenu: function (result) {
			$("#modal-window", this.$el).html(this.modalTemplate({title: "Add connection"}));
			$("#modalDialog", this.$el).show();
            var newConnection = new ConnectionModel();
            this.model.set("selectedConnection", newConnection);
            $(".modal-body", this.$el).html(this.theConnectionTemplate({connection: newConnection.attributes, createOrUpdateConnection: true}));
		},
        addConnectionField: function (events) {
        	var connField = this.model.get("selectedConnection").get("requiredFields");
            this.model.get("selectedConnection").get("requiredFields").push({});
            $(".modal-body", this.$el).html(this.theConnectionTemplate({connection: this.model.get("selectedConnection").attributes, createOrUpdateConnection: true}));
        },
		editConnectionMenu: function (events) {
			$(".modal-body", this.$el).html(this.theConnectionTemplate({createOrUpdateConnection: true, connection: this.model.get("selectedConnection").attributes}));
		},
		showConnectionAction: function (event) {
			var uuid = event.target.id;
			picsureFunctions.getConnection(uuid, function(result) {
                var connection = new ConnectionModel(result);
                connection.set("requiredFields", JSON.parse(connection.get("requiredFields")));
				this.model.set("selectedConnection", connection);

				$("#modal-window", this.$el).html(this.modalTemplate({title: "Connection info"}));
				$("#modalDialog", this.$el).show();
				$(".modal-body", this.$el).html(this.theConnectionTemplate({createOrUpdateConnection: false, connection: connection.attributes}));
			}.bind(this));
		},
        saveConnectionAction: function (e) {
            e.preventDefault();
            var uuid = this.$('input[name=uuid]').val();
            var label = this.$('input[name=label]').val();
			var id = this.$('input[name=id]').val();
			var subPrefix = this.$('input[name=subPrefix]').val();
			var requiredFields = this.$('input[name=requiredFields]').val();
			var connections = [{
				label: label,
				id: id,
				subPrefix: subPrefix,
                requiredFields: requiredFields
			}];
            var requestType = "POST";
			if (uuid) {
                requestType = "PUT";
                connections[0].uuid = uuid;
			}
            picsureFunctions.createOrUpdateConnection(connections, requestType, function(result) {
                session.loadSessionVariables();
                this.render();
            }.bind(this));
        },
		deleteConnection: function (event) {
			var uuid = this.$('input[name=uuid]').val();
			notification.showConfirmationDialog(function () {
				picsureFunctions.deleteConnection(uuid, function (response) {
					this.render()
				}.bind(this));

			}.bind(this));
		},
		closeDialog: function () {
			// cleanup
			this.model.unset("selectedConnection");
			$("#modalDialog").hide();
		},
		render : function(){
			this.$el.html(this.template({}));
			picsureFunctions.getConnection(null, function(connections){
				this.displayConnections(connections);
			}.bind(this));
		}
	});

	return {
		View : connectionManagementView,
		Model: connectionManagementModel
	};
});